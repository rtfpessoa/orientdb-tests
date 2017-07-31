import java.security.SecureRandom
import java.util.UUID

import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.metadata.schema.OType
import gremlin.scala._
import org.apache.tinkerpop.gremlin.orientdb._

import scala.util.Random

object Test {

  def main(args: Array[String]): Unit = {
    val plocal: (String, String, String) = {
      val dbName = s"test-${System.currentTimeMillis}.db"
      val dbPath = s"target/database/$dbName"
      val dbType = "plocal"
      (s"$dbType:$dbPath", "admin", "admin")
    }

    val memory: (String, String, String) = {
      val dbPath = s"test-${System.currentTimeMillis}"
      val dbType = "memory"
      (s"$dbType:$dbPath", "admin", "admin")
    }

    val remote: (String, String, String) = {
      val dbPath = s"127.0.0.1/test3"
      val dbType = "remote"
      (s"$dbType:$dbPath", "root", "1234")
    }

    val (dbUrl, dbUser, dbPassword): (String, String, String) = remote

    val graphOrient: OrientGraph = new OrientGraphFactory(dbUrl, dbUser, dbPassword)
      .setupPool(1, 10)
      .getNoTx()

    val graph = graphOrient.asScala()

    createVertexClasses(graphOrient)
    createEdgeClasses(graphOrient)

    val commit1: Vertex = graph.withVertex(Gen.commits().head)
    val commit2: Vertex = graph.withVertex(Gen.commits().head)
    val commit3: Vertex = graph.withVertex(Gen.commits().head)
    val commit4: Vertex = graph.withVertex(Gen.commits().head)
    val commit5: Vertex = graph.withVertex(Gen.commits().head)

    commit1 --- "isFirstParent" --> commit2
    commit2 --- "isFirstParent" --> commit3
    commit2 --- "isFirstParent" --> commit4
    commit3 --- "isSecondParent" --> commit5
    commit4 --- "isFirstParent" --> commit5

    val pattern1: Vertex = graph.withVertex(Gen.patterns().head)
    val pattern2: Vertex = graph.withVertex(Gen.patterns().head)

    val result1: Vertex = graph.withVertex(Gen.results(pattern1).head)
    val result2: Vertex = graph.withVertex(Gen.results(pattern2).head)
    val result3: Vertex = graph.withVertex(Gen.results(pattern2).head)

    commit1 --- "hasNew" --> result1
    commit1 --- "hasNew" --> result2
    commit2 --- "hasFixed" --> result2

    commit2 --- "hasUnchanged" --> result1
    commit3 --- "hasUnchanged" --> result1

    commit4 --- "hasFixed" --> result1
    commit4 --- "hasNew" --> result3

    commit5 --- "hasUnchanged" --> result3

    println(
      graph
        .V
        .toList
        .head
        .keys
    )
  }

  object Commit {
    val Class: String = "Commit"
    val UUID: Key[String] = Key[String]("uuid")
  }

  object Pattern {
    val Class: String = "Pattern"
    val InternalId: Key[String] = Key[String]("internalId")
    val CategoryType: Key[String] = Key[String]("categoryType")
    val Level: Key[String] = Key[String]("level")
  }

  object Result {
    val Class: String = "Result"
    val Filename: Key[String] = Key[String]("filename")
    val Line: Key[Int] = Key[Int]("line")
    val Pattern: Key[ORID] = Key[ORID]("pattern")
  }

  object Gen {
    val rnd: Random = new Random(new SecureRandom())
    val categories: Array[String] = Array("Security", "CodeStyle", "ErrorProne", "Performance")
    val levels: Array[String] = Array("Info", "Warn", "Error")

    def string(nr: Int = 1): String = rnd.alphanumeric.take(nr).mkString

    def int(min: Int = 0, max: Int = Int.MaxValue): Int = rnd.nextInt(max - min) + min

    def category: String = categories(int(max = categories.length))

    def level: String = levels(int(max = levels.length))

    def commits(nr: Int = 1): List[(String, List[KeyValue[_]])] = {
      (1 to nr).map { _ =>
        (
          Commit.Class,
          List(
            Commit.UUID -> UUID.randomUUID().toString
          )
        )
      }(collection.breakOut)
    }

    def patterns(nr: Int = 1): List[(String, List[KeyValue[_]])] = {
      (1 to nr).map { _ =>
        (
          Pattern.Class,
          List(
            Pattern.InternalId -> string(10)
            , Pattern.CategoryType -> category
            , Pattern.Level -> level
          )
        )
      }(collection.breakOut)
    }

    def results(pattern: Vertex, nr: Int = 1): List[(String, List[KeyValue[_]])] = {
      (1 to nr).map { _ =>
        (
          Result.Class,
          List(
            Result.Filename -> string(10)
            , Result.Line -> int(max = 100000)
            , Result.Pattern -> pattern.id.asInstanceOf[ORID]
          )
        )
      }(collection.breakOut)
    }
  }


  def createVertexClasses(graph: OrientGraph): Unit = {
    val schema = graph.getRawDatabase.getMetadata.getSchema

    val vClass = schema.getClass("V")

    val commitClass = schema.getOrCreateClass("V_Commit", vClass)

    commitClass.createProperty("UUID", OType.STRING).setMandatory(true)


    val patternClass = schema.getOrCreateClass("V_Pattern", vClass)

    patternClass.createProperty("internalId", OType.STRING).setMandatory(true)
    patternClass.createProperty("categoryType", OType.STRING).setMandatory(true)
    patternClass.createProperty("level", OType.STRING).setMandatory(true)


    val resultClass = schema.getOrCreateClass("V_Result", vClass)

    resultClass.createProperty("filename", OType.STRING).setMandatory(true)
    resultClass.createProperty("line", OType.INTEGER).setMandatory(true)
    resultClass.createProperty("pattern", OType.LINK).setMandatory(true)
  }

  def createEdgeClasses(graph: OrientGraph): Unit = {
    val schema = graph.getRawDatabase.getMetadata.getSchema

    val eClass = schema.getClass("E")

    val interface = schema.createAbstractClass("commitResult", eClass)

    schema.createClass("E_hasUnchanged", interface)
    schema.createClass("E_hasFixed", interface)
    schema.createClass("E_hasNew", interface)
  }


  implicit class GraphOps(graph: ScalaGraph) {
    def withVertex(vertex: (String, Seq[KeyValue[_]])): Vertex = {
      vertex match {
        case (label, props) => graph.+(label, props: _*)
      }
    }
  }

}
