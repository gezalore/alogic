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

import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.FlowControlTypes._
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.core.Symbols.Symbol
import com.argondesign.alogic.core.enums.EntityVariant
import com.argondesign.alogic.typer.TypeAssigner

import scala.collection.mutable

final class DefaultStorage(implicit cc: CompilerContext) extends TreeTransformer {

  // Set of output ports accessed through port methods or directly
  private[this] val accessedSet = mutable.Set[Symbol]()

  // Set of output ports connected with '->'
  private[this] val connectedSet = mutable.Set[Symbol]()

  private[this] var inConnect = false

  private[this] var entityVariant: EntityVariant.Type = _

  override def enter(tree: Tree): Option[Tree] = {
    tree match {
      case DefnEntity(_, variant, _) =>
        entityVariant = variant

      case _: EntConnect =>
        assert(!inConnect)
        inConnect = true

      case ExprSym(symbol) if symbol.kind.isOut =>
        if (inConnect) {
          connectedSet add symbol
        } else {
          accessedSet add symbol
        }

      case _ =>
    }
    None
  }

  override def transform(tree: Tree): Tree = tree match {
    case _: EntConnect =>
      assert(inConnect)
      inConnect = false
      tree

    case decl @ DeclOut(symbol, _, fc, StorageTypeDefault) =>
      val newSt = if (entityVariant == EntityVariant.Ver) {
        StorageTypeWire
      } else if (connectedSet contains symbol) {
        StorageTypeWire
      } else if (accessedSet contains symbol) {
        fc match {
          case FlowControlTypeReady  => StorageTypeSlices(List(StorageSliceFwd))
          case FlowControlTypeAccept => StorageTypeWire
          case _                     => StorageTypeReg
        }
      } else {
        StorageTypeWire
      }
      TypeAssigner(decl.copy(st = newSt) withLoc decl.loc)

    case _ => tree
  }

  override def finalCheck(tree: Tree): Unit = {
    assert(!inConnect)
    tree visitAll {
      case node @ DeclOut(_, _, _, StorageTypeDefault) =>
        cc.ice(node, "Default storage type remains")
    }
  }
}

object DefaultStorage extends PairTransformerPass {
  val name = "default-storage"
  def transform(decl: Decl, defn: Defn)(implicit cc: CompilerContext): (Tree, Tree) = {
    (decl, defn) match {
      case (dcl: DeclEntity, _: DefnEntity) =>
        if (dcl.decls.isEmpty) {
          // If no decls, then there is nothing to do
          (decl, defn)
        } else {
          // Perform the transform
          val transformer = new DefaultStorage
          // First transform the defn
          val newDefn = transformer(defn)
          // Then transform the decl
          val newDecl = transformer(decl)
          (newDecl, newDefn)
        }
      case _ => (decl, defn)
    }
  }
}
