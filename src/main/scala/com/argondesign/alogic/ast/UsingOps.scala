////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.ast

import com.argondesign.alogic.ast.Trees._

trait UsingOps { this: Using =>

  def cpy(expr: Expr): Using = this match {
    case node: UsingOne     => node.copy(expr = expr)
    case node: UsingAll     => node.copy(expr = expr)
    case node: UsingGenBody => node.copy(expr = expr)
  }

}
