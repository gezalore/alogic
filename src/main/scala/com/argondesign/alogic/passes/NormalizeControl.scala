////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// DESCRIPTION:
// Normalize control function control statements
// - Add implicit 'fence' in empty branches of 'if' and 'case' statements
// - Add default 'fence' if it does not exist in 'case' statements
// - Replace calls in tail position with 'goto'
// - Replace 'fence'/'break' in final control statements with 'return'
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.ast.StatelessTreeTransformer
import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.TypeAssigner
import com.argondesign.alogic.core.Symbol
import com.argondesign.alogic.core.enums.EntityVariant
import com.argondesign.alogic.util.unreachable

object NormalizeControlTransform extends StatelessTreeTransformer {

  private def convertBreak(stmts: List[Stmt]): List[Stmt] = stmts map convertBreak

  private def convertBreak(stmt: Stmt): Stmt = stmt match {
    // Convert 'break' to 'return
    case _: StmtBreak => TypeAssigner(StmtReturn(comb = false, None) withLoc stmt.loc)

    // Nested statements, convert each branch
    case StmtBlock(body) =>
      TypeAssigner(StmtBlock(convertBreak(body)) withLoc stmt.loc)

    case s @ StmtIf(_, ts, es) =>
      TypeAssigner {
        s.copy(thenStmts = convertBreak(ts), elseStmts = convertBreak(es)) withLoc stmt.loc
      }

    case s @ StmtCase(_, cases) =>
      val newCases = cases map {
        case c @ CaseRegular(_, stmts) =>
          TypeAssigner(c.copy(stmts = convertBreak(stmts)) withLoc c.loc)
        case c @ CaseDefault(stmts) =>
          TypeAssigner(c.copy(stmts = convertBreak(stmts)) withLoc c.loc)
        case _: CaseSplice => unreachable
      }
      TypeAssigner(s.copy(cases = newCases) withLoc s.loc)

    // Leave alone the rest (including nested 'loop')
    case _ => stmt
  }

  private def convertFinal(stmts: List[Stmt]): List[Stmt] =
    stmts.init concat convertFinal(stmts.last)

  private def convertFinal(stmt: Stmt): Iterator[Stmt] = {
    require(stmt.tpe.isCtrlStmt || {
      stmt match {
        // Comb statement looking unreachable in final position means it will be
        // reached if the function is called. Accept it. will convert to control below.
        case StmtSplice(AssertionUnreachable(Some(true), _, _)) => true
        case _                                                  => false
      }
    })
    stmt match {
      // 'return' and 'goto' are OK
      case _: StmtReturn | _: StmtGoto =>
        Iterator.single(stmt)

      //  'unreachable' is also ok, but convert to control statement
      case StmtSplice(a: AssertionUnreachable) =>
        Iterator.single(
          TypeAssigner(
            StmtSplice(
              TypeAssigner(a.copy(knownComb = Some(false)) withLocOf a)
            ) withLocOf stmt
          )
        )

      // Convert final 'fence' to 'Comment + return'. The comment is there to
      // prevent potential removal of an empty state if the function ends in
      // '<ControlStatement>; fence;'
      case _: StmtFence =>
        Iterator(
          TypeAssigner(StmtComment("@@@KEEP@@@") withLoc stmt.loc),
          TypeAssigner(StmtReturn(comb = false, None) withLoc stmt.loc)
        )

      // Convert final 'call' to 'goto' (tail call)
      case StmtExpr(expr) =>
        expr match {
          case call: ExprCall => Iterator(StmtGoto(call) regularize stmt.loc)
          case _              => unreachable
        }

      // Convert 'break' in final loop to 'return'
      case StmtLoop(body) =>
        Iterator.single(TypeAssigner(StmtLoop(convertBreak(body)) withLoc stmt.loc))

      // Nested statements, convert each branch
      case StmtBlock(body) =>
        Iterator.single(TypeAssigner(StmtBlock(convertFinal(body)) withLoc stmt.loc))

      case s @ StmtIf(_, ts, es) =>
        Iterator.single {
          TypeAssigner {
            s.copy(thenStmts = convertFinal(ts), elseStmts = convertFinal(es)) withLoc stmt.loc
          }
        }

      case s @ StmtCase(_, cases) =>
        val newCases = cases map {
          case c @ CaseRegular(_, stmts) =>
            TypeAssigner(c.copy(stmts = convertFinal(stmts)) withLoc c.loc)
          case c @ CaseDefault(stmts) =>
            TypeAssigner(c.copy(stmts = convertFinal(stmts)) withLoc c.loc)
          case _: CaseSplice => unreachable
        }
        Iterator.single(TypeAssigner(s.copy(cases = newCases) withLoc s.loc))

      // The rest are either invalid in final position of have been removed
      // by earlier passes
      case _ => unreachable
    }
  }

  override def enter(tree: Tree): Option[Tree] = tree match {
    case _: Expr => Some(tree)
    case _       => None
  }

  override def transform(tree: Tree): Tree = tree match {

    ////////////////////////////////////////////////////////////////////////////
    // Add implicit fences in empty default branches
    ////////////////////////////////////////////////////////////////////////////

    case stmt @ StmtIf(_, _, Nil) if stmt.tpe.isCtrlStmt =>
      val fence = TypeAssigner(StmtFence() withLoc tree.loc)
      TypeAssigner(stmt.copy(elseStmts = fence :: Nil) withLoc tree.loc)

    case stmt: StmtCase
        if stmt.tpe.isCtrlStmt && !stmt.hasDefault && !stmt.coversAllWithoutDefault =>
      val fence = TypeAssigner(StmtFence() withLoc tree.loc)
      val default = TypeAssigner(CaseDefault(fence :: Nil) withLoc stmt.loc)
      TypeAssigner(stmt.copy(cases = stmt.cases appended default) withLoc stmt.loc)

    ////////////////////////////////////////////////////////////////////////////
    // Normalize final statements in control functions
    ////////////////////////////////////////////////////////////////////////////

    case defn @ DefnFunc(symbol, _, body) if symbol.kind.isCtrlFunc =>
      TypeAssigner(defn.copy(body = convertFinal(body)) withLoc defn.loc)

    case _ => tree
  }

}

object NormalizeControl extends EntityTransformerPass(declFirst = true) {
  val name = "normalize-control"

  override def skip(decl: DeclEntity, defn: DefnEntity): Boolean = defn.variant != EntityVariant.Fsm

  def create(symbol: Symbol)(implicit cc: CompilerContext): TreeTransformer =
    NormalizeControlTransform
}
