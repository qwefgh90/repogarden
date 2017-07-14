package io.github.qwefgh90.repogarden.victims

import io.github.qwefgh90.repogarden.victims
import java.nio.file._
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.collection.CollectResult
import io.github.qwefgh90.repogarden.victims.util.Booter
import io.github.qwefgh90.repogarden.victims.util.ConsoleDependencyGraphDumper
import com.typesafe.scalalogging._
import org.eclipse.aether.graph.DependencyVisitor
import scala.collection.JavaConverters._
import org.apache.maven.model.building._
import org.eclipse.aether.graph._
import org.eclipse.aether.util.artifact.SubArtifact
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.collection.CollectResult
import io.github.qwefgh90.repogarden.victims.util.Booter
import io.github.qwefgh90.repogarden.victims.util.ConsoleDependencyGraphDumper
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.ArtifactDescriptorResult
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils
import org.eclipse.aether.util.graph.transformer.ConflictResolver
import scala.concurrent._
import scala.concurrent.blocking
import scala.io._
import java.io._
import rx.lang.scala._
import rx.lang.scala.observables._
import rx.lang.scala.observers._
import rx.lang.scala.schedulers._
import rx.lang.scala.subjects._
import rx.lang.scala.subscriptions._
import scala.collection._
import scala.annotation.tailrec

package maven { 
  object MvnResult extends Enumeration {
    type MvnResult = Value
    val Success, Fail = Value
  }
  import MvnResult._

  case class ExecutionResult(log: Observable[String], result: Future[MavenResult]){}
  case class MavenResult(resultCode: MvnResult, outputPath: Option[Path]){}
  case class LevelLine(seq: Int, level: Int, line: String)
  case class Found(line: LevelLine, parents: List[LevelLine])

  trait Visitor[A,B] {
    var acc: B
    def enter(e: A, stack: List[A])
    def leave(e: A)
  }

  class DependencyLoader (mvnPath: Path, implicit val ec: ExecutionContext) {

    private val logger = Logger(classOf[DependencyLoader])

    def findParents(iterator: Iterator[LevelLine], contain: LevelLine => Boolean): List[Found] = {
      traverseLevelLines(iterator, new Visitor[LevelLine, List[Found]] {
        override var acc: List[Found] = Nil
        override def enter(levelLine: LevelLine, stack: List[LevelLine]){
          if(contain(levelLine))
            acc = (Found(levelLine, stack) :: acc)
        }
        override def leave(levelLine: LevelLine){}
      })
    }

    def createLevelLineFromMvnOutput(iterator: Iterator[String]): Iterator[LevelLine] = {
      val headLevelPattern = (ch: Char) => (ch == ' ' || ch == '+' || ch == '-' || ch == '|' || ch == '\\')
      iterator.zipWithIndex.map(zip => LevelLine(zip._2 + 1, zip._1.takeWhile(headLevelPattern).length()/3, zip._1.dropWhile(headLevelPattern)))
    }

    def traverseMvnOutput[A >: LevelLine, B](path: Path, visitor: Visitor[A,B]): B = {
      val source = Source.fromFile(path.toFile())
      try{
        traverseMvnOutput(source.getLines, visitor)
      }finally{
        source.close()
      }
    }

    def traverseMvnOutput[A >: LevelLine, B](iterator: Iterator[String], visitor: Visitor[A,B]): B = {
      val levelIterator = createLevelLineFromMvnOutput(iterator)
      traverseLevelLines(levelIterator, visitor)
    }

    def traverseLevelLines[A >: LevelLine, B](levelIterator: Iterator[LevelLine], visitor: Visitor[A,B]): B = {

      @tailrec def go(seq: Int, iterator: Iterator[LevelLine], stack: List[LevelLine]) {
        if(iterator.hasNext){
          val selected = iterator.next
          val LevelLine(seq, level, line) = selected
          val nextStack = if(stack.nonEmpty){
            val LevelLine(seq, lastLevel, lastLine) = stack.head
            val removed = if(lastLevel >= level)
              stack.take(lastLevel - level + 1)
            else
              Nil

            removed.foreach(e => {visitor.leave(e)}) //leave
            visitor.enter(selected, stack.drop(removed.length))//enter

            selected :: stack.drop(removed.length)
          }else{
            visitor.enter(selected, stack)//enter
            val newStack = selected :: Nil
            newStack
          }
          go(seq+1, iterator, nextStack)
        }else{
          stack.foreach(v => visitor.leave(v))//leave remainings
        }
      
      }
      go(1, levelIterator, Nil)
      visitor.acc
    }

    /*
     * run "mvn dependency:tree" in a dir which containing pom.xml
     * and return observable of a result output of command
     * and return a future to hold command exit code.
     */
    def execute(pomFile: Path): ExecutionResult = {
      val builder = new ProcessBuilder(mvnPath.toAbsolutePath().toString(), "dependency:tree", "-DoutputFile=log.txt")
      builder.directory(pomFile.getParent.toFile)
      val process = builder.start()



      /*
       ReplaySubject is used for not blocking execution infinitely.
       Because some native platforms only provide limited buffer size for standard input and output streams, failure to promptly write the input stream or read the output stream of the subprocess may cause the subprocess to block, or even deadlock. https://docs.oracle.com/javase/7/docs/api/java/lang/Process.html#getInputStream()
       */
      val br = new BufferedReader(new InputStreamReader(process.getInputStream()))
      val stream = Stream.continually(br.readLine()).takeWhile(str => str != null)
      val replay = ReplaySubject[String]()

      ExecutionResult(replay
        , Future{blocking(
          {
            try{
              stream.foreach(str => replay.onNext(str))
            }finally{
              br.close()
              replay.onCompleted()
            }
            if(process.waitFor() == 0) MavenResult(MvnResult.Success, Option(pomFile.getParent.resolve("log.txt"))) else MavenResult(MvnResult.Fail, Option.empty)
          }
        )
      }
      )
    }

    /*  def execute(pomPath: Path): Iterator[String] = {
     val source = Source.fromFile(pomPath.toFile)
     source.getLines
     }*/

    /*    Future {
     blocking({
     ""
     })
     }*/


  }



  object DependencyLoader {

    def apply(mvnPath: Path, ec: ExecutionContext): DependencyLoader = {
      return new DependencyLoader(mvnPath, ec)
    }

    /**
      * work with DependencyVisitor
      */
    /*  def getCollectResult(path: Path, visitor: DependencyVisitor): Boolean = {
     val system = Booter.newRepositorySystem();
     val session = Booter.newRepositorySystemSession( system );
     session.setConfigProperty( ConflictResolver.CONFIG_PROP_VERBOSE, true );
     session.setConfigProperty( DependencyManagerUtils.CONFIG_PROP_VERBOSE, true );

	 val modelBuilder = new DefaultModelBuilderFactory().newInstance();

     val pomFile = path.toFile
     val modelRequest = new DefaultModelBuildingRequest()
     modelRequest.setPomFile(pomFile)
     val modelBuildingResult = modelBuilder.build(modelRequest)
     val mavenDependencies = modelBuildingResult.getEffectiveModel.getDependencies

     val dp = mavenDependencies.asScala.map(md => {
     val dependency = new org.eclipse.aether.graph.Dependency(new DefaultArtifact(md.getGroupId, md.getArtifactId, md.getClassifier, md.getType, md.getVersion), md.getScope)
     dependency
     })

     val collectRequest = new CollectRequest()
     collectRequest.setRootArtifact(new DefaultArtifact( "_:_:_" ))
     //    collectRequest.setDependencies( descriptorResult.getDependencies() )
     collectRequest.setDependencies(dp.asJava)
     //    collectRequest.setManagedDependencies(dp.asJava)
     collectRequest.setRepositories( Booter.newRepositories( system, session ) )
     val collectResult = system.collectDependencies( session, collectRequest )

     collectResult.getRoot().accept( visitor );
     }*/
  }
}