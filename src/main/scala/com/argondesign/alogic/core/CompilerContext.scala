////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// DESCRIPTION:
// The CompilerContext holds all mutable state of the compiler.
// Throughout the compiler, the CompilerContext is held in a variable called
// 'cc', which is often passed as an implicit parameter.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.core

import com.argondesign.alogic.ast.Trees.Arg
import com.argondesign.alogic.core.Messages.Fatal
import com.argondesign.alogic.core.Messages.Ice
import com.argondesign.alogic.core.enums.ResetStyle
import com.argondesign.alogic.passes.Passes

import scala.collection.mutable

final class CompilerContext(
    val messageBuffer: MessageBuffer = new MessageBuffer(),
    val settings: Settings = Settings())
    extends Messaging
    with Input
    with Output
    with Profiling
    with StatelessTransforms {

  //////////////////////////////////////////////////////////////////////////////
  // Shorthand for frequently accessed settings
  //////////////////////////////////////////////////////////////////////////////

  val sep: String = settings.sep

  //////////////////////////////////////////////////////////////////////////////
  // Name of reset signal
  //////////////////////////////////////////////////////////////////////////////

  val rst: String = settings.resetStyle match {
    case ResetStyle.AsyncLow | ResetStyle.SyncLow => "rst_n"
    case _                                        => "rst"
  }

  //////////////////////////////////////////////////////////////////////////////
  // Manifest and statistic
  //////////////////////////////////////////////////////////////////////////////

  val manifest: mutable.Map[String, Any] = mutable.LinkedHashMap[String, Any]()

  val statistics: Statistics = new Statistics

  //////////////////////////////////////////////////////////////////////////////
  // Synthetic entity factories
  //////////////////////////////////////////////////////////////////////////////

  val syncRegFactory = new SyncRegFactory
  val syncSliceFactory = new SyncSliceFactory()(this)
  val sramFactory = new SramFactory
  val stackFactory = new StackFactory

  //////////////////////////////////////////////////////////////////////////////
  // Compile the given source
  //////////////////////////////////////////////////////////////////////////////

  def compile(source: Source, loc: Loc, params: List[Arg]): Unit = {
    try {
      Passes(source, loc, params)(cc = this)
    } catch {
      // Catch fatal messages, add them to message buffer for reporting,
      // then return normally from here.
      case message: Fatal => addMessage(message)
      case message: Ice   => addMessage(message)
    }
  }

}
