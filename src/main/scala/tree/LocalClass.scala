package org.omp4j.tree

import org.omp4j.Config
import org.omp4j.grammar._

/** The local class representation */
case class LocalClass(ec: Java8Parser.ClassDeclarationContext, parent: OMPClass, parser: Java8Parser)(implicit conf: Config, ompFile: OMPFile) extends OMPClass(Left(ec), parent, parser) with Nonreflectable
