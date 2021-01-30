////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2018-2019 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Convert StorageTypeDefault to the appropriate concrete value
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.ast.StatefulTreeTransformer
import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.TypeAssigner
import com.argondesign.alogic.core.FlowControlTypes._
import com.argondesign.alogic.core.Messages.Ice
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.core.Symbols.Symbol
import com.argondesign.alogic.core.enums.EntityVariant

import scala.collection.mutable

final class DefaultStorage(implicit cc: CompilerContext) extends StatefulTreeTransformer {

  // Set of output ports connected with '->'
  private[this] val connectedSet = mutable.Set[Symbol]()

  private[this] var inAssign = false

  private[this] var entityVariant: EntityVariant.Type = _

  override def enter(tree: Tree): Option[Tree] = {
    tree match {
      case DefnEntity(_, variant, _) =>
        entityVariant = variant

      case _: EntAssign =>
        assert(!inAssign)
        inAssign = true

      case ExprSym(symbol) if symbol.kind.isOut && inAssign =>
        connectedSet add symbol

      case _ =>
    }
    None
  }

  override def transform(tree: Tree): Tree = tree match {
    case _: EntAssign =>
      assert(inAssign)
      inAssign = false
      tree

    case decl @ DeclOut(symbol, _, fc, StorageTypeDefault) =>
      val newSt = if (connectedSet contains symbol) {
        StorageTypeWire
      } else if (entityVariant != EntityVariant.Fsm) {
        StorageTypeWire
      } else {
        fc match {
          case FlowControlTypeReady => StorageTypeSlices(List(StorageSliceFwd))
          case _                    => StorageTypeReg
        }
      }
      TypeAssigner(decl.copy(st = newSt) withLoc decl.loc)

    case _ => tree
  }

  override def finalCheck(tree: Tree): Unit = {
    assert(!inAssign)
    tree visitAll {
      case node @ DeclOut(_, _, _, StorageTypeDefault) =>
        throw Ice(node, "Default storage type remains")
    }
  }

}

object DefaultStorage extends EntityTransformerPass(declFirst = false, parallel = true) {
  val name = "default-storage"

  override def create(symbol: Symbol)(implicit cc: CompilerContext): TreeTransformer =
    new DefaultStorage
}
