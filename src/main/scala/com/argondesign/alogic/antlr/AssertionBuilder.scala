////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// DESCRIPTION:
// Build an Assertion AST from an Antlr4 parse tree
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.antlr

import com.argondesign.alogic.antlr.AlogicParser._
import com.argondesign.alogic.antlr.AntlrConverters._
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.MessageBuffer
import com.argondesign.alogic.core.Messages.Ice
import com.argondesign.alogic.core.SourceContext

object AssertionBuilder extends BaseBuilder[AssertionContext, Assertion] {

  def apply(
      ctx: AssertionContext
    )(
      implicit
      mb: MessageBuffer,
      sc: SourceContext
    ): Assertion = {
    object Visitor extends AlogicScalarVisitor[Assertion] {
      override def visitAssertionAssert(ctx: AssertionAssertContext): Assertion = {
        val msgOpt = Option.when(ctx.STRING != null) {
          ctx.STRING.txt.slice(1, ctx.STRING.txt.length - 1)
        }
        AssertionAssert(ExprBuilder(ctx.expr), msgOpt) withLoc ctx.loc
      }

      override def visitAssertionStatic(ctx: AssertionStaticContext): Assertion = {
        val msgOpt = Option.when(ctx.STRING != null) {
          ctx.STRING.txt.slice(1, ctx.STRING.txt.length - 1)
        }
        AssertionStatic(ExprBuilder(ctx.expr), msgOpt) withLoc ctx.loc
      }

      override def visitAssertionUnreachable(ctx: AssertionUnreachableContext): Assertion = {
        val msgOpt = Option.when(ctx.STRING != null) {
          ctx.STRING.txt.slice(1, ctx.STRING.txt.length - 1)
        }
        // 'unreachable' in control functions is ambiguous. Everywhere else
        // it behaves as a combinational statement.
        val knownComb = sc match {
          case SourceContext.FuncCtrl => None
          case SourceContext.Unknown =>
            throw Ice("Cannot parse 'unreachable' without source context")
          case _ => Some(true)
        }
        AssertionUnreachable(knownComb, None, msgOpt) withLoc ctx.loc
      }
    }

    Visitor(ctx)
  }

}
