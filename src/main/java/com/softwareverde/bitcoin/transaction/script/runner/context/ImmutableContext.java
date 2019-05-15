package com.softwareverde.bitcoin.transaction.script.runner.context;

import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.input.TransactionInput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.transaction.script.Script;
import com.softwareverde.constable.Const;
import com.softwareverde.json.Json;

public class ImmutableContext implements Context, Const {
    protected final Long _blockHeight;
    protected final Transaction _transaction;

    protected final Integer _transactionInputIndex;
    protected final TransactionInput _transactionInput;
    protected final TransactionOutput _transactionOutput;

    protected final Script _currentScript;
    protected final Integer _currentScriptIndex;
    protected final Integer _scriptLastCodeSeparatorIndex;

    public ImmutableContext(final Context context) {
        _blockHeight = context.getBlockHeight();
        _transaction = context.getTransaction().asConst();
        _transactionInputIndex = context.getTransactionInputIndex();
        _transactionInput = context.getTransactionInput().asConst();
        _transactionOutput = context.getTransactionOutput().asConst();

        final Script currentScript = context.getCurrentScript();
        _currentScript = (currentScript != null ? currentScript.asConst() : null);
        _currentScriptIndex = context.getScriptIndex();
        _scriptLastCodeSeparatorIndex = context.getScriptLastCodeSeparatorIndex();
    }

    @Override
    public Long getBlockHeight() {
        return _blockHeight;
    }

    @Override
    public TransactionInput getTransactionInput() {
        return _transactionInput;
    }

    @Override
    public TransactionOutput getTransactionOutput() {
        return _transactionOutput;
    }

    @Override
    public Transaction getTransaction() {
        return _transaction;
    }

    @Override
    public Integer getTransactionInputIndex() {
        return _transactionInputIndex;
    }

    @Override
    public Script getCurrentScript() {
        return _currentScript;
    }

    @Override
    public Integer getScriptIndex() {
        return _currentScriptIndex;
    }

    @Override
    public Integer getScriptLastCodeSeparatorIndex() {
        return _scriptLastCodeSeparatorIndex;
    }

    @Override
    public ImmutableContext asConst() {
        return this;
    }

    @Override
    public Json toJson() {
        final ContextDeflater contextDeflater = new ContextDeflater();
        return contextDeflater.toJson(this);
    }
}
