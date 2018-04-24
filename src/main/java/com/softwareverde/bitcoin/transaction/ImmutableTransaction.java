package com.softwareverde.bitcoin.transaction;

import com.softwareverde.bitcoin.transaction.input.ImmutableTransactionInput;
import com.softwareverde.bitcoin.transaction.input.TransactionInput;
import com.softwareverde.bitcoin.transaction.locktime.ImmutableLockTime;
import com.softwareverde.bitcoin.transaction.output.ImmutableTransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.type.hash.sha256.ImmutableSha256Hash;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.constable.Const;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.util.ConstUtil;
import com.softwareverde.json.Json;

public class ImmutableTransaction implements Transaction, Const {
    protected final ImmutableSha256Hash _hash;
    protected final Integer _version;
    protected final Boolean _hasWitnessData;
    protected final List<ImmutableTransactionInput> _transactionInputs;
    protected final List<ImmutableTransactionOutput> _transactionOutputs;
    protected final ImmutableLockTime _lockTime;

    public ImmutableTransaction(final Transaction transaction) {
        _hash = transaction.getHash().asConst();
        _version = transaction.getVersion();
        _hasWitnessData = transaction.hasWitnessData();
        _lockTime = transaction.getLockTime().asConst();

        _transactionInputs = ImmutableListBuilder.newConstListOfConstItems(transaction.getTransactionInputs());
        _transactionOutputs = ImmutableListBuilder.newConstListOfConstItems(transaction.getTransactionOutputs());
    }

    @Override
    public Sha256Hash getHash() {
        return _hash;
    }

    @Override
    public Integer getVersion() { return _version; }

    @Override
    public Boolean hasWitnessData() { return _hasWitnessData; }

    @Override
    public final List<TransactionInput> getTransactionInputs() {
        return ConstUtil.downcastList(_transactionInputs);
    }

    @Override
    public final List<TransactionOutput> getTransactionOutputs() {
        return ConstUtil.downcastList(_transactionOutputs);
    }

    @Override
    public ImmutableLockTime getLockTime() { return _lockTime; }

    @Override
    public Long getTotalOutputValue() {
        long totalValue = 0L;

        for (final TransactionOutput transactionOutput : _transactionOutputs) {
            totalValue += transactionOutput.getAmount();
        }

        return totalValue;
    }

    @Override
    public ImmutableTransaction asConst() {
        return this;
    }

    @Override
    public Json toJson() {
        final Json json = new Json();
        json.put("version", Transaction.VERSION);
        json.put("hasWitnessData", _hasWitnessData);

        final Json inputsJson = new Json();
        for (final TransactionInput transactionInput : _transactionInputs) {
            inputsJson.add(transactionInput);
        }
        json.put("inputs", inputsJson);


        final Json outputsJson = new Json();
        for (final TransactionOutput transactionOutput : _transactionOutputs) {
            outputsJson.add(transactionOutput);
        }
        json.put("outputs", outputsJson);

        json.put("locktime", _lockTime);

        return json;
    }
}
