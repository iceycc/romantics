package translator;

import org.apache.commons.lang3.NotImplementedException;
import parser.ast.*;
import parser.util.ParseException;

public class Translator {

    public TAProgram translate(ASTNode astNode) throws ParseException {
        var program = new TAProgram();
        var symbolTable = new SymbolTable();

        for(var child : astNode.getChildren()) {
            translateStmt(program, child, symbolTable);
        }
        return program;
    }

    public void translateStmt(TAProgram program, ASTNode node, SymbolTable symbolTable) throws ParseException {
        switch (node.getType()) {
            case IF_STMT:
                translateIfStmt(program, (IfStmt)node, symbolTable);
                return;
            case ASSIGN_STMT:
                translateAssignStmt(program, node, symbolTable);
                return;
            case DECLARE_STMT:
                translateDeclareStmt(program, node, symbolTable);
                return;
            case FUNCTION_DECLARE_STMT:
                translateFunctionDeclareStmt(program, node, symbolTable);
                return;
            case RETURN_STMT:
                translateReturnStmt(program, node, symbolTable);
                return;
        }
        throw new NotImplementedException("Translator not impl. for " + node.getType());
    }

    private void translateReturnStmt(TAProgram program, ASTNode node, SymbolTable symbolTable) {
        var resultValue = translateExpr(program, node.getChild(0), symbolTable);
        program.add(new TAInstruction(TAOpCodeType.RETURN, null, null, resultValue, null));
    }

    private void translateFunctionDeclareStmt(TAProgram program, ASTNode node, SymbolTable parent) throws ParseException {
        var label = program.addLabel();

        var symbol = new TAValue();
        symbol.lexeme = node.getLexeme();
        symbol.label = (String)label.getArg1();

        label.setArg2(node.getLexeme().getValue());
        var func = (FunctionDeclareStmt)node;
        var args = func.getArgs();
        var symbolTable = new SymbolTable();
        parent.addChild(symbolTable);
        symbolTable.addSymbol(symbol);
        for(var arg : args.getChildren()) {
            symbolTable.createAddressByLexeme(arg.getLexeme());
        }

        for(var child : func.getBlock().getChildren()) {
            translateStmt(program, child, symbolTable);
        }

    }

    private void translateDeclareStmt(TAProgram program, ASTNode node, SymbolTable symbolTable) throws ParseException {
        var lexeme = node.getChild(0).getLexeme();
        if(symbolTable.findSymbolByLexeme(lexeme) != null) {
            throw new ParseException("Syntax Error, Identifier " + lexeme.getValue() + " is already defined.");
        }
        var assigned = symbolTable.createAddressByLexeme(node.getChild(0).getLexeme());
        var expr = node.getChild(1);
        var addr = translateExpr(program, expr, symbolTable);
        program.add(new TAInstruction(TAOpCodeType.COPY, assigned, "=", addr, null));
    }

    private void translateAssignStmt(TAProgram program, ASTNode node, SymbolTable symbolTable) {
        var assigned = symbolTable.createAddressByLexeme(node.getChild(0).getLexeme());
        var expr = node.getChild(1);
        var addr = translateExpr(program, expr, symbolTable);
        program.add(new TAInstruction(TAOpCodeType.COPY, assigned, "=", addr, null));

    }

    public  void translateBlock(TAProgram program, Block block, SymbolTable parent) throws ParseException {
        var symbolTable = new SymbolTable();
        parent.addChild(symbolTable);
        for(var child : block.getChildren()) {
            translateStmt(program, child, symbolTable);
        }
    }

    /**
     * IF语句翻译成三地址代码
     * 1. 表达式
     * 2. 语句块
     * 3. else Tail处理
     */
    public void translateIfStmt(TAProgram program, IfStmt node, SymbolTable symbolTable) throws ParseException {
        var expr = node.getExpr();
        var exprAddr = translateExpr(program,expr,symbolTable);
        var ifOpCode = new TAInstruction(TAOpCodeType.IF_GOTO, null, null, exprAddr, null);
        program.add(ifOpCode);

        translateBlock(program, (Block)node.getBlock(), symbolTable);

        TAInstruction gotoInstruction = null;
        if(node.getChild(2) != null) {
            gotoInstruction = new TAInstruction(TAOpCodeType.GOTO, null, null, null, null);
            program.add(gotoInstruction);
            var labelEndIf = program.addLabel();
            ifOpCode.setArg2(labelEndIf.getArg1());
        }

        if(node.getElseBlock() != null) {
            translateBlock(program, (Block)node.getElseBlock(), symbolTable);
        } else if(node.getElseIfStmt() != null) {
            translateIfStmt(program, (IfStmt) node.getElseIfStmt(), symbolTable);
        }

        var labelEnd = program.addLabel();
        if(node.getChild(2) == null) {
            ifOpCode.setArg2(labelEnd.getArg1());
        } else {
            gotoInstruction.setArg1(labelEnd.getArg1());
        }
    }



    /**
     * Expr -> Expr1 op Expr2
     *  T: result = Expr1.addr op Expr2.addr
     * Expr1 -> Factor
     *  T: Expr1.addr = symbolTable.find(factor)
     */
    public TAValue translateExpr(
            TAProgram program,
            ASTNode node,
            SymbolTable symbolTable) {

        if(node.isValueType()) {
            var addr = symbolTable.createAddressByLexeme(node.getLexeme());
            node.setProp("addr", addr);
            return addr;
        }
        else if(node.getType() == ASTNodeTypes.CALL_EXPR) {
            var addr = translateCallExpr(program,node,symbolTable);
            node.setProp("addr", addr);
            return addr;
        }
        else if(node instanceof Expr){
            for(var child : node.getChildren()) {
                translateExpr(program,child, symbolTable);
            }
            if(node.getProp("addr") == null) {
                node.setProp("addr", symbolTable.createVariable());
            }

            program.add(new TAInstruction(
                    TAOpCodeType.COPY,
                    (TAValue)(node.getProp("addr")),
                    node.getLexeme().getValue(),
                    (TAValue)(node.getChild(0).getProp("addr")),
                    (TAValue)(node.getChild(1).getProp("addr"))
            ));
            return program.lastOpCode().getResult();
        }
        throw new NotImplementedException("Unexpected node type :" + node.getType());
    }

    private TAValue translateCallExpr(TAProgram program, ASTNode node, SymbolTable symbolTable) {

        var factor = node.getChild(0);
        var returnValue = symbolTable.createVariable();
        var returnAddr = symbolTable.createVariable();
        for(int i = node.getChildren().size()-1; i >= 1; i--) {
            var expr = node.getChildren().get(i);
            var addr = translateExpr(program, (Expr)expr, symbolTable);
            program.add(new TAInstruction(TAOpCodeType.PARAM, null, null, addr, null));
        }
        var funcAddr = symbolTable.findSymbolByLexeme(factor.getLexeme());
        program.add(new TAInstruction(TAOpCodeType.CALL, null, null, funcAddr, null));
        return returnValue;
    }
}