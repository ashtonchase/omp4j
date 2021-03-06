package org.omp4j.test

import java.io.File

import org.antlr.v4.runtime._
import org.omp4j.Config
import org.omp4j.grammar._
import org.omp4j.preprocessor._
import org.omp4j.tree.{OMPVariable, OMPFile}
import org.omp4j.utils.FileTreeWalker

/** Loads given file */
abstract class AbstractLoadedContext(path: String) {
	val file = new File(getClass.getResource(path).toURI.getPath)
	implicit val conf: Config = new Config(Array(file.getAbsolutePath))
	val lexer = new Java8Lexer(new ANTLRFileStream(file.getPath))
	val tokens = new CommonTokenStream(lexer)
	val parser = new Java8Parser(tokens)
	val t = parser.compilationUnit
	val ompFile = new OMPFile(t, parser)
	val directives = (new DirectiveVisitor(tokens, parser)).visit(t)

	/* make jar etc. */
	/**/ private val prep = new Preprocessor()(conf)
	/**/ private val parsed = conf.files.map(f => (f, prep.parseFile(f)))
	/**/ prep.validate(parsed)
	/* end */

	/** Delete temp files */
	def cleanup() = {
		if (conf != null && conf.workDir != null) FileTreeWalker.recursiveDelete(conf.workDir)

	}

	/** Variable string in format: "<type> <identifier>" e.g. "int ok1" etc. */
	protected def varAsText(v: OMPVariable) = s"${v.varType} ${v.name}"

}
