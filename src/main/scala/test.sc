import com.tinkerpop.blueprints.impls.orient.OrientGraphFactory

import scala.util.control.NonFatal
import scala.collection.JavaConversions._

//val factory = new OrientGraphFactory("plocal:test.orientdb").setupPool(1, 10)
val factory = new OrientGraphFactory("remote:127.0.0.1/test", "root", "orientdb").setupPool(1, 10)

//var graph = factory.getTx
//try {
//  //  graph.begin()
//
//  // Patterns
//
//  val pattern1 = graph.addVertex("class:Pattern",
//    "internalId", "eval", "categoryType", "Security", "level", "Warn")
//
//  // Commit 1
//
//  val commit1 = graph.addVertex("class:Commit", "uuid", "abcd1234")
//
//  val result1 = graph.addVertex("class:Result",
//    "filename", "/tmp/codacy/file1.js",
//    "line", "56",
//    "pattern", pattern1
//  )
//
//  graph.addEdge("class:ResultType", commit1, result1, "new")
//
//  // Commit 2
//
//  val commit2 = graph.addVertex("class:Commit", "uuid", "efgh5678")
//
//  val result2 = graph.addVertex("class:Result",
//    "filename", "/tmp/codacy/file2.js",
//    "line", "2",
//    "pattern", pattern1
//  )
//
//  graph.addEdge("class:ResultType", commit2, result1, "fixed")
//  graph.addEdge("class:ResultType", commit2, result2, "new")
//
//  //  graph.commit()
//} catch {
//  case NonFatal(_) =>
//} finally {
//  graph.shutdown()
//}

var graph2 = factory.getTx
try {
  //  graph.begin()

  val cenas2 = graph2.traverse().execute().toList.map {
    v =>
      v
  }.length

  val cenas = graph2.getEdges("ResultType.label", "fixed").toList.map {
    v =>
      v
  }.length

  //  graph.commit()

  //  cenas2
  cenas

} catch {
  case NonFatal(e) =>
    e.printStackTrace()
    List.empty
} finally {
  graph2.shutdown()
}
