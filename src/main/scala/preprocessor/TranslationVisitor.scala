package org.omp4j.preprocessor

import scala.io.Source
import scala.util.control.Breaks._
import scala.collection.mutable.Stack

import org.antlr.v4.runtime.atn._
import org.antlr.v4.runtime.tree._
import org.antlr.v4.runtime._

import org.omp4j.Config
import OMPVariableType._
import org.omp4j.exception._
import org.omp4j.preprocessor.grammar._

/** Listener for directive application */
class TranslationVisitor(tokens: CommonTokenStream, parser: Java8Parser, tree: Java8Parser.CompilationUnitContext)(implicit conf: Config) extends Java8BaseVisitor[Unit] {

	/** Reflected file structure */
	private lazy val ompFile = new OMPFile(tree, parser)

	/** List of directives */
	private lazy val directives = (new DirectiveVisitor(tokens, parser)).visit(tree)

	/** Directive translator */
	private lazy val translator = new Translator(tokens, parser, directives, ompFile)

	/** Rewriter for directive expansions*/
	private lazy val rewriter = new TokenStreamRewriter(tokens)

	/** Directive currently being proccesed*/
	private var currentDirective: Directive = null

	/** Stack of nested classes (currently in) */
	private val clStack = Stack[String]()

	/** Set of local variables */
	private var locals = Set[OMPVariable]()

	/** Set of variables to be added to context*/
	private var captured = Set[OMPVariable]()

	/** Name of OMPContext variable; TODO: unique*/
	private var contextName = "ompContext"

	/** Name of OMPContext class; TODO: unique*/
	private var contextClassName = "OMPContext"

	/** Does 'this' keywork appears in parallel statement? */
	private var capturedThis = false

	/** Run translator and resturn modified source as String */
	def translate: String = {
		visit(tree)
		rewriter.getText()
	}
	
	/** Translate statements having directive */
	override def visitStatement(ctx: Java8Parser.StatementContext) = {

		// already getting directive
		if (currentDirective != null) {
			super.visitStatement(ctx)	// continue visiting
		} else {	// no directive
			directives.find(_.ctx == ctx) match {	// TODO: nested directives

				// accessing new directive
				case Some(d) => {
					// set things up
					currentDirective = d
					
					// TODO: method params
					translator.getPossiblyInheritedLocals(ctx).foreach( l =>
						locals += new OMPVariable(l.variableDeclarators().variableDeclarator(0).variableDeclaratorId().getText(), l.`type`().getText())
					)

					// work the statement
					super.visitStatement(ctx)

					// parallelize!
					val first = if (capturedThis) "public " + clStack.head + " THAT;\n" else ""
					val second = if (capturedThis) contextName + ".THAT = this;\n" else ""

					val toPrepend =
						"/* === OMP CONTEXT === */\n" + 
						"class " + contextClassName + " {\n" + 
							(for {c <- captured} yield "public " + c.varType + " " + c.meaning + "_" + c.name + ";\n").toList.mkString + 
							first + 
						"}\n" +
						"final " + contextClassName + " " + contextName + " = new " + contextClassName + "();\n" + 
						second + 
						(for {c <- captured} yield contextName + "." + c.meaning + "_" + c.name + " = " + c.name + ";\n").toList.mkString + 
						"/* === /OMP CONTEXT === */\n"

					rewriter.insertBefore(ctx.start, toPrepend)

					// reset
					currentDirective = null
					capturedThis = false
					captured = Set()
					locals = Set()
				}
				case None => super.visitStatement(ctx)	// continue visiting
			}
		}
	}

	override def visitClassDeclaration(ctx: Java8Parser.ClassDeclarationContext) = {
		clStack.push(ctx.Identifier().getText())
		super.visitClassDeclaration(ctx)
		clStack.pop()
	}

	// TODO: http://docs.oracle.com/javase/tutorial/java/javaOO/anonymousclasses.html

	override def visitPrimary(ctx: Java8Parser.PrimaryContext) = {
		if (currentDirective == null) super.visitPrimary(ctx)
		else {

			// globals (not actually functional, TODO)
			var meaning = OMPVariableType.Class	// Primary meaning (class/local/...)
			var classType = "Object"	// extracted variable type (if really variable)
			var id = ""	// extracted variable name (if really variable)
			try {
				val identifier = ctx.Identifier()
				if (identifier != null) {
					id = identifier.getText()
					val clazz = ompFile.getClass(clStack)
					val fields = clazz.allFields

					(locals find (_.name == id)) match {
						case Some(v) => {
							meaning = OMPVariableType.Local
							classType = v.varType;
						}
						case None => {
							(fields find (_.name == id)) match {
								case Some(v) => {
									meaning = OMPVariableType.Field
									classType = v.varType;
								}
								case None => ;
							}
						}
					}
				
				}
				else if (ctx.getText() == "this") meaning = OMPVariableType.This
				else meaning = OMPVariableType.Liter

			} catch {
				// TODO: exceptions?
				case e: Exception => println(e.getMessage())
			} finally {
				if (meaning == OMPVariableType.Field || meaning == OMPVariableType.Local) {
					rewriter.insertBefore(ctx.start, contextName + "." + meaning + "_")
					captured += new OMPVariable(id, classType, meaning)
				} else if (meaning == OMPVariableType.This) {
					rewriter.replace(ctx.start, ctx.stop, contextName + ".THAT")
					capturedThis = true
				}

				super.visitPrimary(ctx)
			}
		}
	}


}
