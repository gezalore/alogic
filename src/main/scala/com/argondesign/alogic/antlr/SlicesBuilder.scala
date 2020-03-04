////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2019 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.antlr

import com.argondesign.alogic.antlr.AlogicParser._
import com.argondesign.alogic.antlr.AntlrConverters._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.util.unreachable

import scala.jdk.CollectionConverters._
import scala.util.ChainingSyntax

object SlicesBuilder extends BaseBuilder[SlicesContext, List[StorageSlice]] with ChainingSyntax {
  def apply(ctx: SlicesContext)(implicit cc: CompilerContext): List[StorageSlice] = {
    ctx.slice.asScala.toList map {
      _.text match {
        case "bubble" => StorageSliceBub
        case "fslice" => StorageSliceFwd
        case "bslice" => StorageSliceBwd
        case _        => unreachable
      }
    }
  }
}
