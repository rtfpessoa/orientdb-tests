package controllers

import play.api.i18n.I18nSupport
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class ApplicationController(cc: ControllerComponents) extends AbstractController(cc) with I18nSupport {

  implicit lazy val ec: ExecutionContext = cc.executionContext

  def index(): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    Future(Ok(views.html.index()))
  }

}
