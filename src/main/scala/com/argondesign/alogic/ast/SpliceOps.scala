////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.ast

import com.argondesign.alogic.ast.Trees._

trait SpliceObjOps { self: Splice.type =>

  final def unapply(tree: Tree): Option[Spliceable] = tree match {
    case splice: Splice => Some(splice.tree)
    case _              => None
  }

}
