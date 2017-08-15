package io.github.qwefgh90.repogarden.web.test
import play.api.inject.ApplicationLifecycle
import play.api.cache._
import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import org.scalatest._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test._
import play.api.test.Helpers._
import java.io._
import play.api.Mode
import io.github.qwefgh90.repogarden.web.service._
import io.github.qwefgh90.repogarden.web.dao._
import java.util.Base64
import play.api.mvc._
import play.core.server.Server
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.Configuration
import scala.concurrent._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.routing.sird.UrlContext
import io.github.qwefgh90.repogarden.web.controllers._
import play.Logger
import net.sf.ehcache.CacheManager;
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util._
import org.eclipse.egit.github.core._
import io.github.qwefgh90.repogarden.web.model.Implicits._
import io.github.qwefgh90.repogarden.bp.Boilerplate._
import play.api.Application
import java.util.concurrent._

class ControllerSpec extends PlaySpec with BeforeAndAfterAll {//with GuiceOneAppPerSuite {
  //it's hack for solving a global cache manager issue
  //After test, app must shutdown a instance of CacheManager
  /*lifeCycle.addStopHook(() => {
   Future{
   CacheManager.getInstance().shutdown();
   }
   })*/

//  override def fakeApplication(): Application

  val app = new GuiceApplicationBuilder()
	.in(Mode.Test)
	.build()

  override def beforeAll() {
    println("Before!")  // start up your web server or whatever
  }

  override def afterAll() {
    app.stop()
    println("After!")  // shut down the web server
  }         

  "Controller" should {

    val homeController = app.injector.instanceOf[HomeController]
    val encryption = app.injector.instanceOf[Encryption]
    val authService = app.injector.instanceOf[AuthService]
    val configuration = app.injector.instanceOf[Configuration]
    val context = app.injector.instanceOf[ExecutionContext]
    val cache = app.injector.instanceOf[AsyncCacheApi]
    val githubProvider = app.injector.instanceOf[GithubServiceProvider]


    val switchDao = app.injector.instanceOf[SwitchDao]
    Await.result(switchDao.create(), Duration(10, TimeUnit.SECONDS))

    val oauthToken = System.getProperties.getProperty("oauthToken")
	val oauthTokenOpt = if(oauthToken == null)
	  Option.empty
	else
	  Option(oauthToken)
	
	require(oauthTokenOpt.isDefined)

    "return unauthorized code to a unauthenticated user" in {
      val userInfoResponse = homeController.userInfo.apply(FakeRequest())
      status(userInfoResponse) mustBe UNAUTHORIZED
      session(userInfoResponse).isEmpty mustBe true
      val userInfoResponse2 = homeController.userInfo.apply(FakeRequest().withSession("signed" -> "no signed"))
      status(userInfoResponse2) mustBe UNAUTHORIZED
    }
    
    "return ok, if a authenticated user try" in {
      val user = new User()
      user.setId(1234)
      user.setLogin("login")
      user.setEmail("email")
      user.setName("name")
      user.setAvatarUrl("avatar")
      cache.sync.set(user.getId.toString, oauthToken)
      val userInfo = homeController.userInfo.apply(FakeRequest().withSession("signed" -> "signed", "user" -> Json.toJson(user)(userWritesToSession).toString))
      status(userInfo) mustBe OK
    }

    "return repositories" in {
      val result = Server.withRouter() {
        case play.api.routing.sird.POST(p"/login/oauth/access_token") => Action {
          Results.Ok(Json.obj("access_token" -> oauthToken, "token_type" -> "type"))
        }
      } { implicit port =>
        WsTestClient.withClient { mockClient =>
          val mockAuthService = new AuthService(configuration, encryption, mockClient, context, "")
          val authController = new AuthController(mockAuthService, context, cache, githubProvider)

          val fr = FakeRequest().withJsonBody(Json.parse("""{"code":"code", "state":"state", "clientId":"clientId"}"""))
          val result = authController.accessToken.apply(fr)

          status(result) mustBe OK
          session(result).data("signed") mustBe "signed"
          val user = session(result).data("user")

          val result3 = homeController.getOnlyRepositories(FakeRequest().withSession("signed" -> "signed", "user" -> user))
          timer{
            (contentAsJson(result3) \\ "yn").foreach(yn =>{
              assert(yn.as[Boolean] == false)
            })

            status(result3)(3 seconds) mustBe OK
          }((before, after) => {
            Logger.debug(s"getOnlyRepositories() takes ${after-before} millis")
          })
        }
      }
    }

    "return session data to a user who is authenticated" in {
      val result = Server.withRouter() {
        case play.api.routing.sird.POST(p"/login/oauth/access_token") => Action {
          Results.Ok(Json.obj("access_token" -> oauthToken, "token_type" -> "type"))
        }
      } { implicit port =>
        WsTestClient.withClient { mockClient =>
          val mockAuthService = new AuthService(configuration, encryption, mockClient, context, "")
          val authController = new AuthController(mockAuthService, context, cache, githubProvider)

          val fr = FakeRequest().withJsonBody(Json.parse("""{"code":"code", "state":"state", "clientId":"clientId"}"""))
          val result = authController.accessToken.apply(fr)

          status(result) mustBe OK
          session(result).data("signed") mustBe "signed"
        }
      }
      Logger.debug(s"async result is ${result}")
    }
  }
}
