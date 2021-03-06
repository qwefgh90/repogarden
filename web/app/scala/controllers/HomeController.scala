package io.github.qwefgh90.repogarden.web.controllers

import play.api.cache._
import javax.inject._
import play.api._
import play.api.mvc._
import io.github.qwefgh90.repogarden.web.controllers._
import scala.concurrent.ExecutionContext
import scala.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import play.api.libs.json._
import io.github.qwefgh90.repogarden.web.model.Implicits._
import io.github.qwefgh90.repogarden.web.model.{UserInformation, Switch}
import io.github.qwefgh90.repogarden.web.service._
import io.github.qwefgh90.repogarden.web.dao._
import io.github.qwefgh90.repogarden.web.actor._
import play.api.libs.streams.ActorFlow
import akka.actor._
import akka.stream.Materializer

/**
  * This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
class HomeController @Inject()(builder: ActionBuilder, cache: AsyncCacheApi, githubProvider: GithubServiceProvider, typoDao: TypoDao ,switchDao: SwitchDao, @Named("pub-actor") pubActor: ActorRef, configuration: Configuration)(implicit ec: ExecutionContext, implicit val actorSystem: ActorSystem, materializer: Materializer) extends Controller with SameOriginCheck {

  Await.ready(switchDao.create().flatMap(any => typoDao.create()), Duration(10, java.util.concurrent.TimeUnit.SECONDS))

  /**
    * Verify and accept a request
    * and then subscribe a channel.
    */
  def ws = WebSocket.acceptOrResult[JsValue, JsValue] { rh =>
    val channelIdOpt = rh.getQueryString("ch")
    val userOpt = builder.checker.getUserFromSession(rh)
    val tokenOpt = userOpt.flatMap{user => builder.checker.getTokenOfUser(user)}
    Future.successful{
      if(!sameOriginCheck(rh, configuration))
        Left(Forbidden)
      else if(channelIdOpt.isEmpty)
        Left(BadRequest)
      else if(tokenOpt.isEmpty)
        Left(Unauthorized) 
      else
        Right(ActorFlow.actorRef { out =>
          ClientActor.props(out, rh, channelIdOpt.get, userOpt.get)
        })
    }
  }

  /**
    * Create an Action to render an HTML page.
    *
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/`.
    */
  //  def index = Action { implicit request =>
  // Ok(io.github.qwefgh90.repogarden.web.views.html.index()).withNewSession
  // }

  /*
   * Get my profile.
   */
  def userInfo = (builder andThen builder.UserAction) { implicit request =>
    Ok(Json.toJson(request.user))
  }

  /*
   * Get my repositories.
   */
  def getOnlyRepositories = (builder andThen builder.UserAction).async { implicit request =>
    val tokenFuture = cache.get[String](request.user.getId.toString)
    tokenFuture.map({ tokenOpt =>
      if(tokenOpt.isDefined){
        val githubService = githubProvider.getInstance(tokenOpt.get)
        val result = Json.toJson(githubService.getAllRepositories.map(repository => {
          Json.toJson(repository) match {
            case obj: JsObject => {
              val ynOpt = Await.result(switchDao.select(request.user.getId, repository.getId), 10 seconds).map(_.yn)
              obj + ("activated" -> JsBoolean(ynOpt.getOrElse(false)))
            }
          }
        }))
        Ok(result)
      }else{
        Logger.warn(s"${request.user.getEmail} / ${request.user.getId} unauthorized")
        Unauthorized
      }
    }).recover({
      case e: Exception => {
        Logger.warn(e.toString)
        BadRequest
      }
    })
  }

  /*
   * Get a list of branches
   */
  def getBranches(owner: String, name: String) = (builder andThen builder.UserAction andThen builder.PermissionCheckAction(owner, name)).async { implicit request =>
    val tokenFuture = cache.get[String](request.user.getId.toString)
    tokenFuture.map({ tokenOpt =>
      if(tokenOpt.isDefined){
        val githubService = githubProvider.getInstance(tokenOpt.get)
        val list = githubService.getBranchesByName(owner, name)
        Ok(Json.toJson(list))
      }else{
        Logger.warn(s"${request.user.getEmail} / ${request.user.getId} unauthorized")
        Unauthorized
      }
    })
  }

  /*
   * Get a commit by sha.
   */
  def getCommit(owner: String, name: String, sha: String) = (builder andThen builder.UserAction andThen builder.PermissionCheckAction(owner, name)) { implicit request =>
    val token = request.token
    val githubService = githubProvider.getInstance(token)
    val repoOpt = githubService.getRepository(owner, name)
    repoOpt.map(repo => {
      val commitOpt = githubService.getCommit(repo, sha)
      commitOpt.map(commit => Ok(Json.toJson(commit)(commitWritesToBrowser))).getOrElse({BadRequest("a requested commit does not exists.")})
    }).getOrElse(BadRequest("a requested commit not exists."))

  }

  /*
   * Get a tree by commit sha.
   * The tree doesn't contain contents.
   */
  def getTreeByCommitSha(owner: String, name: String, commitSha: String) = (builder andThen builder.UserAction andThen builder.PermissionCheckAction(owner, name)) { implicit request =>
    val token = request.token
    val githubService = githubProvider.getInstance(token)
    val repoOpt = githubService.getRepository(owner, name)
    repoOpt.map(repo => {
      val treeOpt = githubService.getTreeByCommitSha(repo, commitSha)
      treeOpt.map(tree => Ok(Json.toJson(tree)(treeExWritesToBrowser))).getOrElse(BadRequest("a requested tree does not exists"))
    }).getOrElse(BadRequest("a requested tree does not exists"))
  }

  def updateRepositoryActivated(owner: String, name: String) = (builder andThen builder.UserAction andThen builder.PermissionCheckAction(owner, name)) { implicit request =>
    val body: AnyContent = request.body
    val jsonBody: Option[JsValue] = body.asJson
    val token = request.token
    if(jsonBody.isDefined){
      val activatedOpt = (jsonBody.get \ "activated").asOpt[Boolean]
      activatedOpt.map(activated => {
        val githubService = githubProvider.getInstance(token)
        val repoOpt = githubService.getRepository(owner, name)
        repoOpt.map(repo => repo.getId).map{repoId =>
          val opResult = scala.util.Try(Await.result(switchDao.insertOrUpdate(Switch(request.user.getId, repoId, activated)), Duration(10, TimeUnit.SECONDS)))
          opResult.transform(v => scala.util.Try(Ok)
            ,ex => {
              Logger.error("", ex)
              scala.util.Try(InternalServerError)
            }
          ).get
        }.getOrElse{
          BadRequest
        }
      }).getOrElse{
        BadRequest("Expecting parameters")
      }
    }else{
      BadRequest("Expecting application/json request body")
    }
  }

}

trait SameOriginCheck {

  /**
    * Checks that the WebSocket comes from the same origin.  This is necessary to protect
    * against Cross-Site WebSocket Hijacking as WebSocket does not implement Same Origin Policy.
    *
    * See https://tools.ietf.org/html/rfc6455#section-1.3 and
    * http://blog.dewhurstsecurity.com/2013/08/30/security-testing-html5-websockets.html
    */
  def sameOriginCheck(rh: RequestHeader, configuration: Configuration): Boolean = {
    rh.headers.get("Origin") match {
      case Some(originValue) if originMatches(originValue, configuration) =>
        Logger.debug(s"originCheck: originValue = $originValue")
        true

      case Some(badOrigin) =>
        Logger.error(s"originCheck: rejecting request because Origin header value ${badOrigin} is not in the same origin")
        false

      case None =>
        Logger.error("originCheck: rejecting request because no Origin header found")
        false
    }
  }


  /**
    * Returns true if the value of the Origin header contains an acceptable value.
    *
    * This is probably better done through configuration same as the allowedhosts filter.
    */
  def originMatches(origin: String, configuration: Configuration): Boolean = {
    val acceptList = configuration.get[Seq[String]]("play.acceptOriginList")
    acceptList.exists(e => origin.contains(e))
  }
}
