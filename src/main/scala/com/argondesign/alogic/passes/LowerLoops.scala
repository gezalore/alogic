////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2018 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Rewrite do/while/for loops to 'loop' loops.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.typer.TypeAssigner
import com.argondesign.alogic.util.FollowedBy

final class LowerLoops(implicit cc: CompilerContext) extends TreeTransformer with FollowedBy {

//  override def enter(tree: Tree): Unit = tree match {
//    case _ =>
//  }

  override def transform(tree: Tree): Tree = tree match {

    case StmtDo(cond, body) => {
      val test = StmtIf(cond, StmtFence(), Some(StmtBreak()))
      StmtLoop(body :+ test) regularize tree.loc
    }

    case StmtWhile(cond, body) => {
      val test = StmtIf(cond, StmtFence(), Some(StmtBreak()))
      val loop = StmtLoop(body :+ test)
      StmtIf(cond, loop, None) regularize tree.loc
    }

    case StmtFor(inits, Some(cond), steps, body) => {
      val test = StmtIf(cond, StmtFence(), Some(StmtBreak()))
      val loop = StmtLoop(body ::: (steps :+ test))
      StmtBlock(inits :+ StmtIf(cond, loop, None)) regularize tree.loc
    }

    case StmtFor(inits, None, steps, body) => {
      StmtBlock(inits :+ StmtLoop(body ::: steps)) regularize tree.loc
    }

    // Nodes who have rewritten children need types
    case node if !node.hasTpe => TypeAssigner(node)

    case _ => tree
  }

  override def finalCheck(tree: Tree): Unit = {
    tree visit {
      case node: StmtFor   => cc.ice(node, "for statement remains after LowerLoops")
      case node: StmtDo    => cc.ice(node, "do statement remains after LowerLoops")
      case node: StmtWhile => cc.ice(node, "while statement remains after LowerLoops")
    }
  }

}