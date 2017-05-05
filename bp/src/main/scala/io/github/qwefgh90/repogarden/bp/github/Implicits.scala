package io.github.qwefgh90.repogarden.bp.github

import scala.collection.JavaConverters._

import org.eclipse.egit.github.core._
import org.eclipse.egit.github.core.service._
import java.util.Base64
import scala.concurrent.Future
import scala.util.Try

object Implicits {
	class ContentsServiceEx(contentsService: ContentsService){
	  /**
	   * @param repoProvider repository provider
	   * @param path if path is null, iterate contents from root. Otherwise, iterate contents from path
	   * @param recursive whether to iterate all sub directories
	   * @return a list of contents     
	   */
		def getFiles(repoProvider: IRepositoryIdProvider, path: String, ref: String, recursive: Boolean): List[RepositoryContents] = {
			val contentList = contentsService.getContents(repoProvider, path, ref).asScala.toList
			contentList.flatMap{content => 
			  if(content.getType == RepositoryContents.TYPE_DIR && recursive == true) 
			    getFiles(repoProvider, content.getPath, ref, true) 
			  else 
			    List(content) 
			}
		}
	}
	
	class RepositoryContentsEx(repositoryContent: RepositoryContents){
	  def getContentSync(repository: Repository, dataService: DataService) = {
  	  val sha: String = repositoryContent.getSha
  	  val blob = dataService.getBlob(repository, sha)
  	  val encoding = repositoryContent.getEncoding
      val content: String = blob.getEncoding match {
      	case Blob.ENCODING_BASE64 => 
      	  if(encoding == null) new String(Base64.getMimeDecoder.decode(blob.getContent))
      	  else new String(Base64.getMimeDecoder.decode(blob.getContent), encoding)
        case Blob.ENCODING_UTF8 => blob.getContent
  	  }
  	  content
  	}
	}
	
	class VersionString(versionStr: String) extends Ordered[VersionString]{
	  val version = versionStr.trim()
	  def compare(that: VersionString) = {
	    val target = that.version
	    val versionNumberList = version.split("\\.").toList.filter(component => {Try(component.toInt).isSuccess})
	    val targetVersionNumberList = target.split("\\.").toList.filter(component => Try(component.toInt).isSuccess)
	    val zippedList = versionNumberList.zipAll(targetVersionNumberList, "0", "0")
	    zippedList.foldLeft(0)((result: Int, tuple) => {
	      val comp1 = tuple._1.toInt
	      val comp2 = tuple._2.toInt
	      if(result == 0)
	        comp1.compare(comp2)
	      else
	        result
	    })
	  }
	  
	  override def equals(other: Any) = {
	    other match {
	      case that: VersionString => {
	    	  val target = that.version
	    		val versionNumberList = version.split("\\.").toList.filter(component => {Try(component.toInt).isSuccess})
	    		val targetVersionNumberList = target.split("\\.").toList.filter(component => Try(component.toInt).isSuccess)
	    		val zippedList = versionNumberList.zipAll(targetVersionNumberList, "0", "0")
	    		val equality = zippedList.forall((tuple) => { tuple._1 == tuple._2 })
	        (that.canEqual(this) 
	            && equality)
	      }
	      case _ => false
	    }
	  }
	  
	  override def hashCode: Int = this.version.hashCode
	  
	  def canEqual(other: Any): Boolean = {
	    other.isInstanceOf[VersionString]
	  }
	}
	class StringEx(str: String){
	  def toVersion = { new VersionString(str) }
	}
	
	/**
	 * A converter from ContentsService to ContentsServiceEx
	 */
	implicit def extendContentsService(service: ContentsService) = new ContentsServiceEx(service)
	implicit def extendRepositoryContents(content: RepositoryContents) = new RepositoryContentsEx(content)
	implicit def extendString(str: String) = new StringEx(str)
}