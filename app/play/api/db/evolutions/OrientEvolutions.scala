/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.db.evolutions

import java.sql._

import play.api._
import play.api.db.evolutions.DatabaseUrlPatterns._
import play.api.db.evolutions.OrientDatabaseUrlPatterns._
import play.api.db.{DBApi, Database, PooledDatabase}
import play.api.libs.Collections
import play.core.WebCommands

import scala.util.control.Exception.ignoring
import scala.util.control.NonFatal
import scala.util.matching.Regex

class OrientApplicationEvolutions(
                                   config: EvolutionsConfig,
                                   reader: EvolutionsReader,
                                   evolutions: EvolutionsApi,
                                   dynamicEvolutions: DynamicEvolutions,
                                   dbApi: DBApi,
                                   environment: Environment,
                                   webCommands: WebCommands)
  extends ApplicationEvolutions(config, reader, evolutions, dynamicEvolutions, dbApi, environment, webCommands) {

  private val logger = Logger(classOf[ApplicationEvolutions])

  /**
    * Checks the evolutions state. Called on construction.
    */
  override def start(): Unit = {

    webCommands.addHandler(new EvolutionsWebCommands(evolutions, reader, config))

    // allow db modules to write evolution files
    dynamicEvolutions.create()

    dbApi.databases().foreach(runEvolutions)
  }

  private def runEvolutions(database: Database): Unit = {
    val db = database.name
    val dbConfig = config.forDatasource(db)
    if (dbConfig.enabled) {
      withLock(database, dbConfig) {
        val schema = dbConfig.schema
        val autocommit = dbConfig.autocommit

        val scripts = evolutions.scripts(db, reader, schema)
        val hasDown = scripts.exists(_.isInstanceOf[DownScript])
        val onlyDowns = scripts.forall(_.isInstanceOf[DownScript])

        if (scripts.nonEmpty && !(onlyDowns && dbConfig.skipApplyDownsOnly)) {

          import Evolutions.toHumanReadableScript

          environment.mode match {
            case Mode.Test => evolutions.evolve(db, scripts, autocommit, schema)
            case Mode.Dev if dbConfig.autoApply => evolutions.evolve(db, scripts, autocommit, schema)
            case Mode.Prod if !hasDown && dbConfig.autoApply => evolutions.evolve(db, scripts, autocommit, schema)
            case Mode.Prod if hasDown && dbConfig.autoApply && dbConfig.autoApplyDowns => evolutions.evolve(db, scripts, autocommit, schema)
            case Mode.Prod if hasDown =>
              logger.warn(s"Your production database [$db] needs evolutions, including downs! \n\n${toHumanReadableScript(scripts)}")
              logger.warn(s"Run with -Dplay.evolutions.db.$db.autoApply=true and -Dplay.evolutions.db.$db.autoApplyDowns=true if you want to run them automatically, including downs (be careful, especially if your down evolutions drop existing data)")

              throw InvalidDatabaseRevision(db, toHumanReadableScript(scripts))

            case Mode.Prod =>
              logger.warn(s"Your production database [$db] needs evolutions! \n\n${toHumanReadableScript(scripts)}")
              logger.warn(s"Run with -Dplay.evolutions.db.$db.autoApply=true if you want to run them automatically (be careful)")

              throw InvalidDatabaseRevision(db, toHumanReadableScript(scripts))

            case _ => throw InvalidDatabaseRevision(db, toHumanReadableScript(scripts))
          }
        }
      }
    }
  }

  private def withLock(db: Database, dbConfig: EvolutionsDatasourceConfig)(block: => Unit): Unit = {
    if (dbConfig.useLocks) {
      val ds = db.dataSource
      val url = db.url
      val c = ds.getConnection
      c.setAutoCommit(false)
      val s = c.createStatement()
      createLockTableIfNecessary(url, c, s, dbConfig)
      lock(url, c, s, dbConfig)
      try {
        block
      } finally {
        unlock(c, s)
      }
    } else {
      block
    }
  }

  private def createLockTableIfNecessary(url: String, c: Connection, s: Statement, dbConfig: EvolutionsDatasourceConfig): Unit = {
    import ApplicationEvolutions._
    val queries = url match {
      case OracleJdbcUrl() =>
        Some((SelectPlayEvolutionsLockSql, List(CreatePlayEvolutionsLockOracleSql), InsertIntoPlayEvolutionsLockSql))
      case MysqlJdbcUrl(_) =>
        Some((SelectPlayEvolutionsLockMysqlSql, List(CreatePlayEvolutionsLockMysqlSql), InsertIntoPlayEvolutionsLockMysqlSql))
      case OrientJdbcUrl() =>
        Some(
          (
            "select lock from ${schema}play_evolutions_lock",
            List(
              "CREATE CLASS ${schema}play_evolutions_lock IF NOT EXISTS",
              "CREATE PROPERTY ${schema}play_evolutions_lock.lock IF NOT EXISTS INTEGER (MANDATORY TRUE)"
            ),
            "insert into ${schema}play_evolutions_lock (`lock`) values (1)"
          )
        )
      case _ =>
        Some((SelectPlayEvolutionsLockSql, List(CreatePlayEvolutionsLockSql), InsertIntoPlayEvolutionsLockSql))
    }
    queries.foreach {
      case (selectScript, createScripts, insertScript) =>
        try {
          val r = s.executeQuery(applySchema(selectScript, dbConfig.schema))
          r.close()
        } catch {
          case _: SQLException =>
            c.rollback()
            createScripts.foreach(cs => s.execute(applySchema(cs, dbConfig.schema)))
            s.executeUpdate(applySchema(insertScript, dbConfig.schema))
        }
    }
  }

  private def lock(url: String, c: Connection, s: Statement, dbConfig: EvolutionsDatasourceConfig, attempts: Int = 5): Unit = {
    import ApplicationEvolutions._
    val lockScripts = url match {
      case MysqlJdbcUrl(_) => lockPlayEvolutionsLockMysqlSqls
      case OrientJdbcUrl() => List.empty
      case _ => lockPlayEvolutionsLockSqls
    }
    try {
      for (script <- lockScripts) s.executeQuery(applySchema(script, dbConfig.schema))
    } catch {
      case e: SQLException =>
        if (attempts == 0) throw e
        else {
          logger.warn("Exception while attempting to lock evolutions (other node probably has lock), sleeping for 1 sec")
          c.rollback()
          Thread.sleep(1000)
          lock(url, c, s, dbConfig, attempts - 1)
        }
    }
  }

  private def unlock(c: Connection, s: Statement): Unit = {
    ignoring(classOf[SQLException])(s.close())
    ignoring(classOf[SQLException])(c.commit())
    ignoring(classOf[SQLException])(c.close())
  }

  // SQL helpers

  private def applySchema(sql: String, schema: String): String = {
    sql.replaceAll("\\$\\{schema}", Option(schema).filter(_.trim.nonEmpty).map(_.trim + ".").getOrElse(""))
  }
}

/**
  * Default implementation of the evolutions API.
  */
class OrientDefaultEvolutionsApi(dbApi: DBApi) extends EvolutionsApi {

  private def databaseEvolutions(name: String, schema: String) = new OrientDatabaseEvolutions(dbApi.database(name), schema)

  def scripts(db: String, evolutions: Seq[Evolution], schema: String): Seq[Script] = databaseEvolutions(db, schema).scripts(evolutions)

  def scripts(db: String, reader: EvolutionsReader, schema: String): Seq[Script] = databaseEvolutions(db, schema).scripts(reader)

  def resetScripts(db: String, schema: String): Seq[Script] = databaseEvolutions(db, schema).resetScripts()

  def evolve(db: String, scripts: Seq[Script], autocommit: Boolean, schema: String): Unit = databaseEvolutions(db, schema).evolve(scripts, autocommit)

  def resolve(db: String, revision: Int, schema: String): Unit = databaseEvolutions(db, schema).resolve(revision)
}

/**
  * Evolutions for a particular database.
  */
class OrientDatabaseEvolutions(database: Database, schema: String = "") {

  import DatabaseUrlPatterns._
  import DefaultEvolutionsApi._

  lazy val dbUrl: String = Option(database).collect {
    case db: PooledDatabase =>
      db.databaseConfig.url
  }.flatten.getOrElse(database.url)

  def scripts(evolutions: Seq[Evolution]): Seq[Script] = {
    if (evolutions.nonEmpty) {
      val application = evolutions.reverse
      val database = databaseEvolutions()

      val (nonConflictingDowns, dRest) = database.span(e => !application.headOption.exists(e.revision <= _.revision))
      val (nonConflictingUps, uRest) = application.span(e => !database.headOption.exists(_.revision >= e.revision))

      val (conflictingDowns, conflictingUps) = Evolutions.conflictings(dRest, uRest)

      val ups = (nonConflictingUps ++ conflictingUps).reverseMap(e => UpScript(e))
      val downs = (nonConflictingDowns ++ conflictingDowns).map(e => DownScript(e))

      downs ++ ups
    } else Nil
  }

  def scripts(reader: EvolutionsReader): Seq[Script] = {
    scripts(reader.evolutions(database.name))
  }

  /**
    * Read evolutions from the database.
    */
  private def databaseEvolutions(): Seq[Evolution] = {
    implicit val connection = database.getConnection(autocommit = true)

    try {
      checkEvolutionsState()
      Collections.unfoldLeft(executeQuery(
        "select id, hash, apply_script, revert_script from ${schema}play_evolutions order by id"
      )) { rs =>
        if (rs.next) {
          Some(
            (
              rs,
              Evolution(rs.getInt(1), Option(rs.getString(3)).getOrElse(""), Option(rs.getString(4)).getOrElse(""))
            )
          )
        } else {
          None
        }
      }
    } finally {
      connection.close()
    }
  }

  def evolve(scripts: Seq[Script], autocommit: Boolean): Unit = {

    implicit val connection: Connection = database.getConnection(autocommit = autocommit)

    def logBefore(script: Script): Unit = {
      script match {
        case UpScript(e) =>
          prepareAndExecute(
            "insert into ${schema}play_evolutions " +
              "(id, hash, applied_at, apply_script, revert_script, state, last_problem) " +
              "values(?, ?, ?, ?, ?, ?, ?)"
          ) { ps =>
            ps.setInt(1, e.revision)
            ps.setString(2, e.hash)
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()))
            ps.setString(4, e.sql_up)
            ps.setString(5, e.sql_down)
            ps.setString(6, "applying_up")
            ps.setString(7, "")
          }

        case DownScript(e) =>
          execute("update ${schema}play_evolutions set state = 'applying_down' where id = " + e.revision)
      }
    }

    def logAfter(script: Script): Boolean = {
      script match {
        case UpScript(e) =>
          execute("update ${schema}play_evolutions set state = 'applied' where id = " + e.revision)
        case DownScript(e) =>
          execute("delete from ${schema}play_evolutions where id = " + e.revision)
      }
    }

    def updateLastProblem(message: String, revision: Int): Boolean = {
      prepareAndExecute("update ${schema}play_evolutions set last_problem = ? where id = ?") { ps =>
        ps.setString(1, message)
        ps.setInt(2, revision)
      }
    }

    checkEvolutionsState()

    var applying = -1
    var lastScript: Script = null

    try {

      scripts.foreach { script =>
        lastScript = script
        applying = script.evolution.revision
        logBefore(script)
        // Execute script
        script.statements.foreach(execute)
        logAfter(script)
      }

      if (!autocommit) {
        connection.commit()
      }

    } catch {
      case NonFatal(e) =>
        val message = e match {
          case ex: SQLException => ex.getMessage + " [ERROR:" + ex.getErrorCode + ", SQLSTATE:" + ex.getSQLState + "]"
          case ex => ex.getMessage
        }
        if (!autocommit) {
          logger.error(message)

          connection.rollback()

          val humanScript = "# --- Rev:" + lastScript.evolution.revision + "," + (if (lastScript.isInstanceOf[UpScript]) "Ups" else "Downs") + " - " + lastScript.evolution.hash + "\n\n" + (if (lastScript.isInstanceOf[UpScript]) lastScript.evolution.sql_up else lastScript.evolution.sql_down)

          throw InconsistentDatabase(database.name, humanScript, message, lastScript.evolution.revision, autocommit)
        } else {
          updateLastProblem(message, applying)
        }
    } finally {
      connection.close()
    }

    checkEvolutionsState()
  }

  /**
    * Checks the evolutions state in the database.
    *
    * @throws NonFatal error if the database is in an inconsistent state
    */
  private def checkEvolutionsState(): Unit = {
    def createPlayEvolutionsTable()(implicit conn: Connection): Unit = {
      try {
        val createScripts = dbUrl match {
          case SqlServerJdbcUrl() => List(CreatePlayEvolutionsSqlServerSql)
          case OracleJdbcUrl() => List(CreatePlayEvolutionsOracleSql)
          case MysqlJdbcUrl(_) => List(CreatePlayEvolutionsMySql)
          case DerbyJdbcUrl() => List(CreatePlayEvolutionsDerby)
          case OrientJdbcUrl() =>
            List(
              "CREATE CLASS ${schema}play_evolutions IF NOT EXISTS",
              "CREATE PROPERTY ${schema}play_evolutions.id IF NOT EXISTS INTEGER (MANDATORY TRUE)",
              "CREATE PROPERTY ${schema}play_evolutions.hash IF NOT EXISTS STRING (MANDATORY TRUE)",
              "CREATE PROPERTY ${schema}play_evolutions.applied_at IF NOT EXISTS DATETIME (MANDATORY TRUE)",
              "CREATE PROPERTY ${schema}play_evolutions.apply_script IF NOT EXISTS STRING",
              "CREATE PROPERTY ${schema}play_evolutions.revert_script IF NOT EXISTS STRING",
              "CREATE PROPERTY ${schema}play_evolutions.state IF NOT EXISTS STRING",
              "CREATE PROPERTY ${schema}play_evolutions.last_problem IF NOT EXISTS STRING"
            )
          case _ => List(CreatePlayEvolutionsSql)
        }

        createScripts.foreach(execute)
      } catch {
        case NonFatal(ex) => logger.warn("could not create ${schema}play_evolutions table", ex)
      }
    }

    val autocommit = true
    implicit val connection = database.getConnection(autocommit = autocommit)

    try {
      val problem = executeQuery("select id, hash, apply_script, revert_script, state, last_problem from ${schema}play_evolutions where state like 'applying_%'")

      if (problem.next) {
        val revision = problem.getInt("id")
        val state = problem.getString("state")
        val hash = problem.getString("hash").take(7)
        val script = state match {
          case "applying_up" => problem.getString("apply_script")
          case _ => problem.getString("revert_script")
        }
        val error = problem.getString("last_problem")

        logger.error(error)

        val humanScript = "# --- Rev:" + revision + "," + (if (state == "applying_up") "Ups" else "Downs") + " - " + hash + "\n\n" + script

        throw InconsistentDatabase(database.name, humanScript, error, revision, autocommit)
      }

    } catch {
      case e: InconsistentDatabase => throw e
      case NonFatal(_) => createPlayEvolutionsTable()
    } finally {
      connection.close()
    }
  }

  def resetScripts(): Seq[Script] = {
    val appliedEvolutions = databaseEvolutions()
    appliedEvolutions.map(DownScript)
  }

  def resolve(revision: Int): Unit = {
    implicit val connection = database.getConnection(autocommit = true)
    try {
      execute("update ${schema}play_evolutions set state = 'applied' where state = 'applying_up' and id = " + revision)
      execute("delete from ${schema}play_evolutions where state = 'applying_down' and id = " + revision)
    } finally {
      connection.close()
    }
  }

  // SQL helpers

  private def executeQuery(sql: String)(implicit c: Connection): ResultSet = {
    c.createStatement.executeQuery(applySchema(sql))
  }

  private def execute(sql: String)(implicit c: Connection): Boolean = {
    c.createStatement.execute(applySchema(sql))
  }

  private def prepareAndExecute(sql: String)(block: PreparedStatement => Unit)(implicit c: Connection) = {
    val ps = c.prepareStatement(applySchema(sql))
    try {
      block(ps)
      ps.execute()
    } finally {
      ps.close()
    }
  }

  private def applySchema(sql: String): String = {
    sql.replaceAll("\\$\\{schema}", Option(schema).filter(_.trim.nonEmpty).map(_.trim + ".").getOrElse(""))
  }

}

/**
  * Defines database url patterns.
  */
private[evolutions] object OrientDatabaseUrlPatterns {
  lazy val OrientJdbcUrl: Regex = "^jdbc:orient:.*".r
}
