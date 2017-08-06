package io.github.qwefgh90.repogarden.web.model
import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.eclipse.egit.github.core._

object Implicits extends RepositoryExtension {
  implicit val userReads = new Reads[User]{
    def reads(json: JsValue) = {
      val idOpt = (json \ "id").asOpt[Int]
      val usernameOpt = (json \ "username").asOpt[String]
      val emailOpt = (json \ "email").asOpt[String]
      val imgUrlOpt = (json \ "imgUrl").asOpt[String]
      val optSeq = Seq(idOpt, usernameOpt, emailOpt, imgUrlOpt)
      val validated = optSeq.forall(_.isDefined)

      if(validated){
        val user = new User()
        user.setId(idOpt.get)
        user.setName(usernameOpt.get)
        user.setEmail(emailOpt.get)
        user.setAvatarUrl(imgUrlOpt.get)
        new JsSuccess(user)
      }else
        JsError(s"It failed to parse json to User object. ${optSeq.zipWithIndex.filter(_._1.isEmpty).mkString}")

    }
  }

  implicit val initialVectorWrites = new Writes[InitialVector] {
    def writes(vec: InitialVector) = Json.obj(
      "client_id" -> vec.client_id,
      "state" -> vec.state
    )
  }
  
  val userWritesToSession = new Writes[org.eclipse.egit.github.core.User] {
    def writes(user: org.eclipse.egit.github.core.User) = Json.obj(
      "id" -> user.getId,
      "email" -> user.getEmail,
      "username" -> user.getName,
      "firstName" -> "",
      "lastName" -> "",
      "expiredDate" -> "",
      "imgUrl" -> user.getAvatarUrl
    )
  }

  implicit val userWritesToBrowser = new Writes[org.eclipse.egit.github.core.User] {
    def writes(user: org.eclipse.egit.github.core.User) = Json.obj(
      "id" -> user.getId,
      "username" -> user.getName,
      "firstName" -> "",
      "lastName" -> "",
      "expiredDate" -> "",
      "imgUrl" -> user.getAvatarUrl
    )
  }

  implicit val branchToBrowser = new Writes[org.eclipse.egit.github.core.RepositoryBranch] {
    def writes(branch: org.eclipse.egit.github.core.RepositoryBranch) = Json.obj(
      "name" -> branch.getName
    )
  }

  implicit val commitWritesToBrowser = new Writes[org.eclipse.egit.github.core.RepositoryCommit] {
    def writes(repoCommit: org.eclipse.egit.github.core.RepositoryCommit) = {
      val commit = repoCommit.getCommit
      Json.obj(
        "sha" -> commit.getSha,
        "message" -> commit.getMessage,
        "date" -> commit.getCommitter.getDate,
        "committerEmail" -> commit.getCommitter.getEmail,
        "committerName" -> commit.getCommitter.getName,
        "url" -> commit.getUrl
      )
    }
  }

  implicit val repositoryWritesToBrowser = new Writes[org.eclipse.egit.github.core.Repository] {
    def writes(repo: org.eclipse.egit.github.core.Repository) = Json.obj(
      "owner" -> repo.getOwner.getName,
      "name" -> repo.getName,
      "accessLink" -> repo.getUrl,
      "activated" -> true
    )
  }

  implicit val cveWritesToBrowser = new Writes[Cve] {
    def writes(cve: Cve) = Json.obj(
      "cve" -> cve.cve,
      "title" -> cve.title,
      "description" -> cve.description,
      "references" -> cve.references
    )
  }

  //SpellCheck
  implicit val spellCheckResultFormat: Format[SpellCheckResult] = (
    (JsPath \ "sentence").format[String] and
      (JsPath \ "positionList").format[List[TypoPosition]]
  )(SpellCheckResult.apply, unlift(SpellCheckResult.unapply))

  implicit val typoPositionFormat: Format[TypoPosition] = (
    (JsPath \ "text").format[String] and
      (JsPath \ "offset").format[Int] and
      (JsPath \ "length").format[Int] and
      (JsPath \ "suggestedList").format[List[String]]
  )(TypoPosition.apply, unlift(TypoPosition.unapply))

}
