package alogic.ast

import alogic.antlr.VParser._

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.antlr.v4.runtime.tree.RuleNode

import alogic.Antlr4Conversions._
import alogic.Message
import alogic.VScalarVisitor
import alogic.VScope
import alogic.ast.AstOps._
import org.antlr.v4.runtime.ParserRuleContext
import scala.collection.mutable.Stack
import alogic.VScalarVisitor

// The aim of the AstBuilder stage is:
//   Build an abstract syntax tree
//   Deal with as many error conditions as possible (while we can easily report error location)
//   Deal with typedefs
//   Deal with variable scope
//   Rewrite go/zxt/sxt/read/write/lock/unlock function calls
//
// We use different visitors for the different things we wish to extract.

////////////////////////////////////////////////////////////////////////////////
// Visitors and data structures required when parsing all kinds of source files
////////////////////////////////////////////////////////////////////////////////
class CommonContext(root: ParserRuleContext) {
  // Build scopes and allocate static variable names
  private[this] val scope = new VScope(root)

  // Construct tree for dotted name, looking up the variable name in the current scope
  object LookUpName extends VScalarVisitor[DottedName] {
    override def visitDotted_name(ctx: Dotted_nameContext) = {
      val (head :: tail) = ctx.es.toList.map(_.text)
      DottedName(scope(ctx, head) :: tail)
    }
  }

  // Construct tree for variable reference, looking up the variable name in the current scope
  object VarRefVisitor extends VScalarVisitor[VarRef] {
    override def visitVarRef(ctx: VarRefContext) = LookUpName(ctx.dotted_name)
    override def visitVarRefIndex(ctx: VarRefIndexContext) = ArrayLookup(LookUpName(ctx.dotted_name), ExprVisitor(ctx.es))
  }

  object ExprVisitor extends VScalarVisitor[Expr] {
    // If applied to a commaexpr node, return a list of the constructed expressions
    def apply(ctx: CommaexprContext): List[Expr] = visit(ctx.expr)

    def const2Num(const: String) = Num(None, None, BigInt(const filter (_ != '_')))
    def tickn2Num(ctx: ParserRuleContext, tickn: String, width: Option[String]): Num = {
      assert(tickn(0) == '\'')
      val widthVal = width filter (_ != '_') map (BigInt(_))
      val signed = tickn(1) == 's'
      val baseChar = if (signed) tickn(2) else tickn(1)
      val base = baseChar match {
        case 'b' => 2
        case 'd' => 10
        case 'h' => 16
        case c   => Message.error(ctx, s"Unknown base '$c'"); 16
      }
      val rest = if (signed) tickn drop 3 else tickn drop 2
      val digits = rest filter (_ != '_')
      val value = BigInt(digits, base)
      // TODO: check value fits in width
      Num(Some(signed), widthVal, value)
    }

    override def visitExprBracket(ctx: ExprBracketContext) = Bracket(visit(ctx.expr))
    override def visitExprUnary(ctx: ExprUnaryContext) = UnaryOp(ctx.op, visit(ctx.expr))
    override def visitExprMulDiv(ctx: ExprMulDivContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprAddSub(ctx: ExprAddSubContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprShift(ctx: ExprShiftContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprCompare(ctx: ExprCompareContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprEqual(ctx: ExprEqualContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprBAnd(ctx: ExprBAndContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprBXor(ctx: ExprBXorContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprBOr(ctx: ExprBOrContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprAnd(ctx: ExprAndContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprOr(ctx: ExprOrContext) = BinaryOp(visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprTernary(ctx: ExprTernaryContext) = TernaryOp(visit(ctx.expr(0)), visit(ctx.expr(1)), visit(ctx.expr(2)))
    override def visitExprRep(ctx: ExprRepContext) = BitRep(visit(ctx.expr(0)), visit(ctx.expr(1)))
    override def visitExprCat(ctx: ExprCatContext) = BitCat(this(ctx.commaexpr))
    override def visitExprVarRef(ctx: ExprVarRefContext) = VarRefVisitor(ctx)
    override def visitExprSlice(ctx: ExprSliceContext) = Slice(VarRefVisitor(ctx.var_ref), visit(ctx.expr(0)), ctx.op, visit(ctx.expr(1)))
    override def visitExprDollar(ctx: ExprDollarContext) = DollarCall(ctx.DOLLARID, this(ctx.commaexpr))
    override def visitExprTrue(ctx: ExprTrueContext) = Num(Some(false), Some(1), 1)
    override def visitExprFalse(ctx: ExprFalseContext) = Num(Some(false), Some(1), 0)
    override def visitExprTrickNum(ctx: ExprTrickNumContext) = tickn2Num(ctx, ctx.TICKNUM, None)
    override def visitExprConstTickNum(ctx: ExprConstTickNumContext) = tickn2Num(ctx, ctx.TICKNUM, Some(ctx.CONSTANT))
    override def visitExprConst(ctx: ExprConstContext) = const2Num(ctx.CONSTANT)
    override def visitExprLiteral(ctx: ExprLiteralContext) = Literal(ctx.LITERAL)

    override def visitExprCall(ctx: ExprCallContext) = {
      val n = LookUpName(ctx.dotted_name)
      val a = this(ctx.commaexpr)
      n match {
        case DottedName(names) if (names.last == "read") => {
          if (a.length > 0)
            Message.error(ctx, s"Interface read takes no arguments (${a.length} found)")
          ReadCall(DottedName(names.init))
        }
        case DottedName(names) if (names.last == "lock") => {
          if (a.length > 0)
            Message.error(ctx, s"Interface lock takes no arguments (${a.length} found)")
          LockCall(DottedName(names.init))
        }
        case DottedName(names) if (names.last == "unlock") => {
          if (a.length > 0)
            Message.error(ctx, s"Interface unlock takes no arguments (${a.length} found)")
          UnlockCall(DottedName(names.init))
        }
        case DottedName(names) if (names.last == "valid" || names.last == "v") => {
          if (a.length > 0)
            Message.error(ctx, s"Accessing valid property takes no arguments (${a.length} found)")
          ValidCall(DottedName(names.init))
        }
        case DottedName(names) if (names.last == "write") => {
          if (a.length != 1)
            Message.error(ctx, s"Interface write takes exactly one argument (${a.length} found)")
          WriteCall(DottedName(names.init), a)
        }
        case DottedName("zxt" :: Nil) => {
          if (a.length != 2)
            Message.error(ctx, s"Zero extend function takes exactly two arguments: number of bits and expression (${a.length} found)")
          Zxt(a(0), a(1))
        }
        case DottedName("sxt" :: Nil) => {
          if (a.length != 2)
            Message.error(ctx, s"Sign extend function takes exactly two arguments: number of bits and expression (${a.length} found)")
          Sxt(a(0), a(1))
        }
        case _ => {
          if (a.length > 0)
            Message.error(ctx, s"State functions take no arguments (${a.length} found)")
          CallExpr(n, a)
        }
      }
    }
  }

  private[this] val typedefs = mutable.Map[String, Type]("state" -> State)

  var entityCtx: EntityContext = null

  object KnownTypeVisitor extends VScalarVisitor[Type] {
    override def visitBoolType(ctx: BoolTypeContext) = IntType(false, 1)
    override def visitIntType(ctx: IntTypeContext) = IntType(true, ctx.INTTYPE.text.tail.toInt)
    override def visitUintType(ctx: UintTypeContext) = IntType(false, ctx.UINTTYPE.text.tail.toInt)
    override def visitIntVType(ctx: IntVTypeContext) = IntVType(true, ExprVisitor(ctx.commaexpr))
    override def visitUintVType(ctx: UintVTypeContext) = IntVType(false, ExprVisitor(ctx.commaexpr))
    override def visitIdentifierType(ctx: IdentifierTypeContext) = {
      val s = ctx.IDENTIFIER.text
      typedefs.getOrElse(s, {
        Message.error(ctx, s"Unknown type '$s'")
        IntType(false, 1)
      })
    }
  }

  private[this] object TypeDefinitionExtractor extends VScalarVisitor[Unit] {
    override def defaultResult = Message.ice("Should be called with Start node")

    override def visitStart(ctx: StartContext) = {
      visit(ctx.typedefinition)
      // we save the parse tree node to save walking the whole parse tree again
      entityCtx = ctx.entity
    }

    override def visitTypedefinition(ctx: TypedefinitionContext) = {
      visit(Option(ctx.typedef))
      visit(Option(ctx.struct))
    }

    override def visitStruct(ctx: StructContext) = {
      val s = ctx.IDENTIFIER.text
      if (typedefs contains s) {
        Message.error(ctx, s"Repeated typedef 'struct $s'")
      }
      val pairs = ctx.fields.toList map { c => c.IDENTIFIER.text -> KnownTypeVisitor(c.known_type) }
      typedefs(s) = Struct(ListMap(pairs: _*))
    }

    override def visitTypedef(ctx: TypedefContext) = {
      val s = ctx.IDENTIFIER.text
      if (typedefs contains s) {
        Message.error(ctx, s"Repeated typedef '$s'")
      }
      typedefs(s) = KnownTypeVisitor(ctx.known_type)
    }
  }

  // Collect type definitions and entityContext)
  TypeDefinitionExtractor(root)

  object DeclVisitor extends VScalarVisitor[Declaration] {
    object SyncTypeVisitor extends VScalarVisitor[SyncType] {
      override def visitSyncReadyBubbleType(ctx: SyncReadyBubbleTypeContext) = SyncReadyBubble
      override def visitWireSyncAcceptType(ctx: WireSyncAcceptTypeContext) = WireSyncAccept
      override def visitSyncReadyType(ctx: SyncReadyTypeContext) = SyncReady
      override def visitWireSyncType(ctx: WireSyncTypeContext) = WireSync
      override def visitSyncAcceptType(ctx: SyncAcceptTypeContext) = SyncAccept
      override def visitSyncType(ctx: SyncTypeContext) = Sync
      override def visitWireType(ctx: WireTypeContext) = Wire
      override val defaultResult = Wire
    }

    override def visitTaskDeclOut(ctx: TaskDeclOutContext) =
      OutDeclaration(SyncTypeVisitor(ctx.sync_type), KnownTypeVisitor(ctx.known_type), scope(ctx, ctx.IDENTIFIER))

    override def visitTaskDeclIn(ctx: TaskDeclInContext) =
      InDeclaration(SyncTypeVisitor(ctx.sync_type), KnownTypeVisitor(ctx.known_type), scope(ctx, ctx.IDENTIFIER))

    override def visitTaskDeclConst(ctx: TaskDeclConstContext) =
      ConstDeclaration(KnownTypeVisitor(ctx.known_type), scope(ctx, ctx.IDENTIFIER), ExprVisitor(ctx.expr))

    override def visitTaskDeclParam(ctx: TaskDeclParamContext) =
      ParamDeclaration(KnownTypeVisitor(ctx.known_type), scope(ctx, ctx.IDENTIFIER), ExprVisitor(ctx.expr))

    override def visitTaskDeclVerilog(ctx: TaskDeclVerilogContext) =
      VerilogDeclaration(KnownTypeVisitor(ctx.known_type()), VarRefVisitor(ctx.var_ref))

    override def visitTaskDecl(ctx: TaskDeclContext) = visit(ctx.decl)

    override def visitDeclNoInit(ctx: DeclNoInitContext) =
      VarDeclaration(KnownTypeVisitor(ctx.known_type()), VarRefVisitor(ctx.var_ref), None)

    override def visitDeclInit(ctx: DeclInitContext) =
      VarDeclaration(KnownTypeVisitor(ctx.known_type()), VarRefVisitor(ctx.var_ref), Some(ExprVisitor(ctx.expr)))
  }

  object VerilogFunctionVisitor extends VScalarVisitor[VerilogFunction] {
    override def visitVerilogFunction(ctx: VerilogFunctionContext) = VerilogFunction(ctx.VERILOGBODY.text.drop(1).dropRight(1))
  }
}

////////////////////////////////////////////////////////////////////////////////
// Builder to handle 'fsm' task definitions
////////////////////////////////////////////////////////////////////////////////
class FsmTaskBuilder(cc: CommonContext) {
  import cc._

  def apply(tree: TaskFSMContext): FsmTask = {

    object LValueVisitor extends VScalarVisitor[Expr] {
      override def visitLValue(ctx: LValueContext) = VarRefVisitor(ctx.var_ref)
      override def visitLValueSlice(ctx: LValueSliceContext) =
        Slice(VarRefVisitor(ctx.var_ref), ExprVisitor(ctx.expr(0)), ctx.op, ExprVisitor(ctx.expr(1)))
      override def visitLValueCat(ctx: LValueCatContext) =
        BitCat(visit(ctx.refs))
    }

    object StatementVisitor extends VScalarVisitor[Stmt] {
      override def visitBlockStmt(ctx: BlockStmtContext) = {
        val stmts = visit(ctx.stmts)
        val ctrlStmts = stmts collect { case s: CtrlStmt => s }
        val combStmts = stmts collect { case s: CombStmt => s }

        (ctrlStmts, combStmts) match { // TODO: Not sure this is the best way to write this
          case (Nil, comb) => CombinatorialBlock(comb)
          case (ctrl, comb) => {
            stmts.last match {
              case s: CtrlStmt => ControlBlock(stmts)
              case s: CombStmt => {
                Message.error(ctx, "A control block must end with a control statement")
                ControlBlock(Nil)
              }
            }
          }
        }
      }

      override def visitDeclStmt(ctx: DeclStmtContext) = DeclVisitor(ctx.decl()) match {
        case s: VarDeclaration => DeclarationStmt(s)
        case _ => {
          Message.error(ctx, "Only variable declarations allowed inside functions")
          DeclarationStmt(VarDeclaration(State, DottedName(List("Unknown")), None))
        }
      }

      override def visitWhileStmt(ctx: WhileStmtContext) = {
        val cond = ExprVisitor(ctx.expr)
        val body = visit(ctx.stmts)
        body.last match {
          case _: CombStmt => Message.error(ctx, "The body of a while loop must end with a control statement")
          case _           =>
        }
        ControlWhile(cond, body)
      }

      override def visitIfStmt(ctx: IfStmtContext) = {
        val cond = ExprVisitor(ctx.expr())
        val thenStmt = visit(ctx.thenStmt)
        val elseStmt = visit(Option(ctx.elseStmt))

        (thenStmt, elseStmt) match {
          case (t: CtrlStmt, None)              => ControlIf(cond, t, None)
          case (t: CtrlStmt, Some(e: CtrlStmt)) => ControlIf(cond, t, Some(e))
          case (t: CombStmt, None)              => CombinatorialIf(cond, t, None)
          case (t: CombStmt, Some(e: CombStmt)) => CombinatorialIf(cond, t, Some(e))
          case _ => {
            Message.error(ctx, "Both branches of an if must be control statements, or both must be combinatorial statements");
            ControlIf(cond, ControlBlock(Nil), None)
          }
        }
      }

      override def visitCaseStmt(ctx: CaseStmtContext) = {

        object DefaultVisitor extends VScalarVisitor[Option[Stmt]] {
          override val defaultResult = None
          override def visitDefaultCase(ctx: DefaultCaseContext) = Some(StatementVisitor(ctx.statement()))
        }

        object CaseVisitor extends VScalarVisitor[Option[Node]] {
          override val defaultResult = None
          override def visitNormalCase(ctx: NormalCaseContext) = {
            val args = ExprVisitor(ctx.commaexpr)
            StatementVisitor(ctx.statement()) match {
              case s: CtrlStmt => Some(ControlCaseLabel(args, s))
              case s: CombStmt => Some(CombinatorialCaseLabel(args, s))
            }
          }
        }

        val test = ExprVisitor(ctx.expr())

        val defaultCase = DefaultVisitor(ctx.cases).flatten match {
          case Nil      => None
          case d :: Nil => Some(d)
          case _        => Message.error(ctx, "More than one 'default' case item specified"); None
        }

        val cases = CaseVisitor(ctx.cases).flatten
        val ctrlCases = cases collect { case s: ControlCaseLabel => s }
        val combCases = cases collect { case s: CombinatorialCaseLabel => s }

        (ctrlCases, combCases, defaultCase) match {
          case (Nil, Nil, None)               => CombinatorialCaseStmt(test, Nil, None)
          case (Nil, Nil, Some(d: CombStmt))  => CombinatorialCaseStmt(test, Nil, Some(d))
          case (Nil, Nil, Some(d: CtrlStmt))  => ControlCaseStmt(test, Nil, Some(d))
          case (Nil, comb, None)              => CombinatorialCaseStmt(test, comb, None)
          case (Nil, comb, Some(d: CombStmt)) => CombinatorialCaseStmt(test, comb, Some(d))
          case (ctrl, Nil, None)              => ControlCaseStmt(test, ctrl, None)
          case (ctrl, Nil, Some(d: CtrlStmt)) => ControlCaseStmt(test, ctrl, Some(d))
          case _ => {
            Message.error(ctx, "Either all or none of the case items must be control statements");
            ControlCaseStmt(test, Nil, None)
          }
        }
      }

      object ForInitVisitor extends VScalarVisitor[(Option[VarDeclaration], CombStmt)] {
        override def visitForInitNoDecl(ctx: ForInitNoDeclContext) = {
          val stmt = StatementVisitor(ctx.assignment_statement) match {
            case s: CombStmt => s
            case _: CtrlStmt => Message.ice("unreachable")
          }
          (None, stmt)
        }
        override def visitDeclInit(ctx: DeclInitContext) = {
          val varDecl = DeclVisitor(ctx).asInstanceOf[VarDeclaration]
          val initExpr = Assign(VarRefVisitor(ctx.var_ref), ExprVisitor(ctx.expr))
          (Some(varDecl), initExpr)
        }
      }

      override def visitForStmt(ctx: ForStmtContext) = {
        val (optDecl, initStmt) = ForInitVisitor(ctx.init)
        val stepStmt = visit(ctx.step) match {
          case s: CombStmt => s
          case _: CtrlStmt => Message.ice("unreachable")
        }
        val forAST = ControlFor(initStmt, ExprVisitor(ctx.cond), stepStmt, visit(ctx.stmts))
        optDecl match {
          case None       => forAST
          case Some(decl) => ControlBlock(DeclarationStmt(decl) :: forAST :: Nil)
        }
      }

      override def visitDoStmt(ctx: DoStmtContext) = ControlDo(ExprVisitor(ctx.expr), visit(ctx.stmts))

      override def visitFenceStmt(ctx: FenceStmtContext) = FenceStmt
      override def visitBreakStmt(ctx: BreakStmtContext) = BreakStmt
      override def visitReturnStmt(ctx: ReturnStmtContext) = ReturnStmt
      override def visitDollarCommentStmt(ctx: DollarCommentStmtContext) = AlogicComment(ctx.LITERAL)
      override def visitGotoStmt(ctx: GotoStmtContext) = GotoStmt(ctx.IDENTIFIER)

      override def visitAssignmentStmt(ctx: AssignmentStmtContext) = visit(ctx.assignment_statement)

      override def visitAssignInc(ctx: AssignIncContext) = Plusplus(LValueVisitor(ctx.lvalue))
      override def visitAssignDec(ctx: AssignDecContext) = Minusminus(LValueVisitor(ctx.lvalue))
      override def visitAssign(ctx: AssignContext) = Assign(LValueVisitor(ctx.lvalue), ExprVisitor(ctx.expr()))
      override def visitAssignUpdate(ctx: AssignUpdateContext) = Update(LValueVisitor(ctx.lvalue), ctx.ASSIGNOP, ExprVisitor(ctx.expr()))

      override def visitExprStmt(ctx: ExprStmtContext) = ExprVisitor(ctx.expr) match {
        case CallExpr(DottedName(target :: xs), args) => {
          if (!args.isEmpty) {
            Message.fatal(ctx, "Function calls in statement position take no arguments")
          }
          if (!xs.isEmpty) {
            Message.fatal(ctx, "Function calls in statement position must use unqualified name")
          }
          CallStmt(target)
        }
        case expr => ExprStmt(expr)
      }
    }

    object FsmTaskVisitor extends VScalarVisitor[FsmTask] {
      object FsmContentVisitor extends VScalarVisitor[Node] {
        override def visitFunction(ctx: FunctionContext) = {
          val name = ctx.IDENTIFIER.text
          val stmts = StatementVisitor(ctx.stmts)
          val body = stmts.last match {
            case _: CtrlStmt => ControlBlock(stmts)
            case _: CombStmt => {
              Message.error(ctx, "A function body must end with a control statement")
              ControlBlock(List(FenceStmt))
            }
          }
          Function(name, body)
        }
        override def visitFenceFunction(ctx: FenceFunctionContext) = {
          val body = StatementVisitor(ctx.stmts) collect {
            case stmt: CombStmt => stmt
            case stmt: CtrlStmt => Message.fatal(ctx, "Body of 'fence' function must not contain control statements")
          }
          FenceFunction(CombinatorialBlock(body))
        }
        override def visitVerilogFunction(ctx: VerilogFunctionContext) = VerilogFunctionVisitor(ctx)
      }

      override def visitTaskFSM(ctx: TaskFSMContext) = {
        val name = ctx.IDENTIFIER.text
        val decls = DeclVisitor(ctx.decls)
        val contents = FsmContentVisitor(ctx.contents)
        val fns = contents collect { case x: Function => x }
        val fencefns = contents collect { case x: FenceFunction => x }
        val vfns = contents collect { case x: VerilogFunction => x }

        if (fencefns.length > 1) {
          Message.error(ctx, s"fsm '$name' has more than 1 fence function defined")
        }

        FsmTask(name, decls, fns, fencefns.headOption, vfns)
      }
    }

    FsmTaskVisitor(tree)
  }
}

////////////////////////////////////////////////////////////////////////////////
// Builder to handle 'verilog' task definitions
////////////////////////////////////////////////////////////////////////////////
class VerilogTaskBuilder(cc: CommonContext) {
  import cc._

  def apply(tree: TaskVerilogContext): VerilogTask = {

    object VerilogTaskVisitor extends VScalarVisitor[VerilogTask] {
      override def visitTaskVerilog(ctx: TaskVerilogContext) = {
        val vfns = ctx.contents.toList collect {
          case c: VerilogFunctionContext => VerilogFunctionVisitor(c)
        }
        VerilogTask(ctx.IDENTIFIER.text, DeclVisitor(ctx.decls), vfns)
      }
    }

    VerilogTaskVisitor(tree)
  }
}

////////////////////////////////////////////////////////////////////////////////
// Builder to handle 'network' definitions
////////////////////////////////////////////////////////////////////////////////
class NetworkTaskBuilder(cc: CommonContext) {
  import cc._

  def apply(tree: NetworkContext): NetworkTask = {

    object DottedNameVisitor extends VScalarVisitor[DottedName] {
      override def visitDotted_name(ctx: Dotted_nameContext) = DottedName(ctx.es.toList map (_.text))
    }

    object ParamAssignVisitor extends VScalarVisitor[ParamAssign] {
      override def visitParam_assign(ctx: Param_assignContext) = ParamAssign(ctx.IDENTIFIER, ExprVisitor(ctx.expr));
    }

    object NetworkVisitor extends VScalarVisitor[NetworkTask] {
      object NetworkContentVisitor extends VScalarVisitor[Node] {
        override def visitTaskFSM(ctx: TaskFSMContext) = ???
        override def visitTaskVerilog(ctx: TaskVerilogContext) = ???
        override def visitConnect(ctx: ConnectContext) = {
          val lhs = DottedNameVisitor(ctx.lhs)
          val rhs = DottedNameVisitor(ctx.rhs)
          Connect(lhs, rhs)
        }
        override def visitInstantiate(ctx: InstantiateContext) = {
          val id = ctx.IDENTIFIER(0).text
          val module = ctx.IDENTIFIER(1).text
          val pas = ParamAssignVisitor(ctx.param_args.param_assign)
          Instantiate(id, module, pas)
        }
        override def visitVerilogFunction(ctx: VerilogFunctionContext) = VerilogFunctionVisitor(ctx)
      }

      override def visitNetwork(ctx: NetworkContext) = {
        val name = ctx.IDENTIFIER.text
        val decls = DeclVisitor(ctx.decls)
        val contents = NetworkContentVisitor(ctx.contents)
        val inst = contents collect { case x: Instantiate => x }
        val conn = contents collect { case x: Connect => x }
        val vfns = contents collect { case x: VerilogFunction => x }

        NetworkTask(name, decls, inst, conn, vfns)
      }
    }

    NetworkVisitor(tree)
  }
}

object AstBuilder {
  def apply(root: ParserRuleContext): Task = {

    val cc = new CommonContext(root)

    lazy val fsmTaskBuilder = new FsmTaskBuilder(cc)
    lazy val verilogTaskBuilder = new VerilogTaskBuilder(cc)
    lazy val networkTaskBuilder = new NetworkTaskBuilder(cc)

    object RootVisitor extends VScalarVisitor[Task] {
      override def visitTaskFSM(ctx: TaskFSMContext) = fsmTaskBuilder(ctx)
      override def visitTaskVerilog(ctx: TaskVerilogContext) = verilogTaskBuilder(ctx)
      override def visitNetwork(ctx: NetworkContext) = networkTaskBuilder(ctx)
    }

    RootVisitor(cc.entityCtx)
  }
}