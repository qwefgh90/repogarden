package io.github.qwefgh90.repogarden.bp.github

import scala.collection.JavaConverters._

import org.eclipse.egit.github.core._
import org.eclipse.egit.github.core.service._
import java.util.Base64
import scala.concurrent.Future
import scala.util.Try
import org.eclipse.aether.util.version._
import org.eclipse.aether.version._
import com.typesafe.scalalogging._
import java.nio.file._
import java.nio.file.StandardOpenOption._
import java.io._
import com.typesafe.scalalogging._
import io.github.qwefgh90.repogarden.bp.github.Implicits._

trait Tree2 {
  private val logger = Logger(classOf[Tree2])

  trait Visitor[A,B] {
    var acc: B
    def enter(a:A, stack:List[A])
    def leave(a:A)
  }

  case class TreeEntryEx(seq: Int, level: Int, name: String, entry: TreeEntry) {
    private var content: String = ""
    private var encoding: String = ""
    private var bytes: Array[Byte] = Array()
    private var sync: Boolean = false

    def getContent: Option[String] = {
      if(!sync)
        throw new IllegalAccessException("The content does not loaded. please call syncContent()")
      if(encoding == Blob.ENCODING_UTF8){
        Option(content)
      }else{
        Option.empty
      }
    }

    def getBytes = {
      if(!sync)
        throw new IllegalAccessException("The content does not loaded. please call syncContent()")
      bytes
    }

    def isSync = sync

    def syncContent(repository: Repository, dataService: DataService){
      if(!sync){
  	    val sha: String = entry.getSha// this.getSha
  	    val blob = dataService.getBlob(repository, sha)
        blob.getEncoding match {
      	  case Blob.ENCODING_BASE64 =>
            bytes = Base64.getMimeDecoder.decode(blob.getContent)
          case Blob.ENCODING_UTF8 =>
            bytes = blob.getContent.getBytes
  	    }
        encoding = blob.getEncoding
        content = new String(bytes, "utf-8")
        sync = true
      }
    }
  }

  object GitTree {
    def apply(list: List[TreeEntry]): GitTree = {
      val newList = list.zipWithIndex.map(e => {
        val level = e._1.getPath.count(_ == '/')
        val lastIndex = (e._1.getPath.lastIndexOf("/"))
        new TreeEntryEx(e._2
          , level
          , e._1.getPath.substring(if(lastIndex == -1) 0 else lastIndex+1)
          , e._1)
      })
      new GitTree(newList)
    }

    def apply(tree: org.eclipse.egit.github.core.Tree): GitTree = {
      apply(tree.getTree.asScala.toList)
    }
  }

  class GitTree private (val list: List[TreeEntryEx]) {

    def traverse[B](visitor: Visitor[TreeEntryEx,B]): B = {
      val levelIterator = list.toIterator
      @annotation.tailrec
      def go(seq: Int, iterator: Iterator[TreeEntryEx], stack: List[TreeEntryEx]) {
        if(iterator.hasNext){
          val selected = iterator.next
          val TreeEntryEx(seq, level, name, line) = selected
          val nextStack = if(stack.nonEmpty){
            val TreeEntryEx(seq, lastLevel, name, lastLine) = stack.head
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

    def syncContents(repository: Repository, dataService: DataService) = {
      val visitor = new Visitor[TreeEntryEx, Unit]{
        override var acc: Unit = Unit
        override def enter(node: TreeEntryEx, stack: List[TreeEntryEx]) =
          node.entry.getType match {
            case TreeEntry.TYPE_BLOB =>
              node.syncContent(repository, dataService)
            case _ =>
          }
        override def leave(node: TreeEntryEx){
        }
      }
      traverse(visitor)
    }

    def writeToFileSystem(dir: Path, filter: TreeEntryEx => Boolean = e => true): Path = {
      val visitor = new Visitor[TreeEntryEx, Path]{
        override var acc: Path = dir
        override def enter(node: TreeEntryEx, stack: List[TreeEntryEx]){
          val parentPath = dir.resolve(stack.reverse.map(node => node.name).mkString(java.io.File.separator))

          node.entry.getType match {
            case TreeEntry.TYPE_TREE => {
              if(filter(node)){
                val directory = parentPath.resolve(node.name)
                if(!Files.exists(directory)){
                  Files.createDirectories(directory)
                }
              }else
                 logger.debug(s"in ${node.entry.getPath.toString}, ${node.entry.getType}, ${node.name} is filtered.")
            }
            case TreeEntry.TYPE_BLOB => {
              if(filter(node)){
                if(Files.exists(parentPath)){
                  val file = parentPath.resolve(node.name)
                  if(!Files.exists(file)){
                    val out = new BufferedOutputStream(Files.newOutputStream(file, CREATE, APPEND))
                    try{
                      out.write(node.getBytes, 0, node.getBytes.length);
                    } finally{
                      out.close()
                    }
                  }
                }
              }else
                 logger.debug(s"in ${node.entry.getPath.toString}, ${node.entry.getType}, ${node.name} is filtered.")
            }
          }
        }
        override def leave(node: TreeEntryEx){}
      }
      traverse(visitor)
    }

  }

}

trait Tree {
  private val logger = Logger(classOf[Tree])

  trait Visitor[A,B] {
    var acc: B
    def enter(a:A, stack:List[A])
    def leave(a:A)
  }

  abstract class Node(val get: RepositoryContentsExtend)
  object NilNode extends Node(new RepositoryContentsExtend)
  case class TreeNode(override val get: RepositoryContentsExtend, children: List[Node]) extends Node(get)
  case class TerminalNode(override val get: RepositoryContentsExtend) extends Node(get)

  /**
    * A repesentation of a tree which contains only blob, tree.
    */
  case class Tree(children: List[Node]) {
    def traverse[B](visitor: Visitor[Node, B]): B = {
      def go(node: Node, stack: List[Node]): Unit = node match {
        case terminalNode: TerminalNode => {
          visitor.enter(terminalNode, stack)
          visitor.leave(terminalNode)
        }
        case treeNode: TreeNode => {
          visitor.enter(treeNode, stack)
          treeNode.children.foreach(child => {
            go(child, treeNode::stack)
          })
          visitor.leave(treeNode)
        }
      }
      children.foreach(child => {
        go(child, Nil)
      })
      visitor.acc
    }

    def syncContents(repository: Repository, dataService: DataService) = {
      val visitor = new Visitor[Node, Unit]{
        override var acc: Unit = Unit
        override def enter(node: Node, stack: List[Node]) =
          node match {
            case node: TerminalNode =>
              node.get.syncContent(repository, dataService)
            case _ =>
          }
        override def leave(node: Node){
        }
      }
      traverse(visitor)
    }

    def writeToFileSystem(dir: Path, filter: PartialFunction[Node, Boolean] = {case _ => true}): Path = {
      val visitor = new Visitor[Node, Path]{
        override var acc: Path = dir
        override def enter(node: Node, stack: List[Node]){
          val parentPath = dir.resolve(stack.reverse.map(node => node.get.getName).mkString(java.io.File.separator))
          val safeFilter: PartialFunction[Node, Boolean] = {
            case node: TreeNode => if(!filter.isDefinedAt(node)) true else filter(node)
            case node: TerminalNode => if(!filter.isDefinedAt(node)) true else filter(node)
            case _ => true
          }

          node match {
            case node: TreeNode => {
              if(safeFilter(node)){
                val directory = parentPath.resolve(node.get.getName)
                if(!Files.exists(directory)){
                  Files.createDirectories(directory)
                }
              }else
                 logger.debug(s"in ${node.get.getPath}, ${node.get.getType}, ${node.get.getName} is filtered.")
            }
            case node: TerminalNode => {
              if(safeFilter(node)){
                if(Files.exists(parentPath)){
                  val exNode = node.get
                  val file = parentPath.resolve(node.get.getName)
                  if(!Files.exists(file)){
                    val out = new BufferedOutputStream(Files.newOutputStream(file, CREATE, APPEND))
                    try{
                      out.write(exNode.getBytes, 0, exNode.getBytes.length);
                    } finally{
                      out.close()
                    }
                  }
                }
              }else
                 logger.debug(s"in ${node.get.getPath}, ${node.get.getType}, ${node.get.getName} is filtered.")
            }
          }
        }
        override def leave(node: Node){}
      }
      traverse(visitor)
    }
  }
}
