////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.antlr

import com.argondesign.alogic.core.Loc
import com.argondesign.alogic.core.Source
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.TokenSource

class AlogicToken(
    source: org.antlr.v4.runtime.misc.Tuple2[_ <: TokenSource, CharStream], // Antlr4 source pair
    kind: Int, // Token type
    channel: Int, // Channel index
    val alogicSource: Source, // Source that this token was lexed from
    start: Int, // Start index of token in text of alogicSource
    stop: Int, // End index (inclusive) of token in text of alogicSource
    val file: String, // source file name (can be changed by #line)
    val trueFileName: String // source file name (not changed by #line)
  ) extends CommonToken(source, kind, channel, start, stop) {

  def txt = getText

  lazy val loc: Loc = Loc(
    file,
    getLine,
    alogicSource,
    getStartIndex,
    getStopIndex + 1,
    getStartIndex,
    Some(trueFileName)
  )

}
