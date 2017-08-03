package di

import controllers.AssetsComponents
import play.api.ApplicationLoader.Context
import play.api.db.evolutions._
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.{Application, BuiltInComponentsFromContext, LoggerConfigurator, ApplicationLoader => PlayApplicationLoader}
import play.filters.HttpFiltersComponents

class ApplicationLoader extends PlayApplicationLoader {
  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    new ApplicationComponents(context).application
  }
}

class ApplicationComponents(context: Context)
  extends BuiltInComponentsFromContext(context)
    with AssetsComponents
    with DBComponents
    with EvolutionsComponents
    with HikariCPComponents
    with HttpFiltersComponents {

  override val evolutionsApi: EvolutionsApi = new OrientDefaultEvolutionsApi(dbApi)
  override val applicationEvolutions: ApplicationEvolutions = new OrientApplicationEvolutions(evolutionsConfig, evolutionsReader, evolutionsApi, dynamicEvolutions, dbApi, environment, webCommands)

  lazy val applicationController = new controllers.ApplicationController(controllerComponents)

  lazy val router = new _root_.router.Routes(httpErrorHandler, applicationController, assets)

}
