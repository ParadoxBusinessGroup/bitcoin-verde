package com.softwareverde.bitcoin.transaction.script.opcode;

import com.softwareverde.bitcoin.bip.Buip55;
import com.softwareverde.bitcoin.bip.HF20171113;
import com.softwareverde.bitcoin.bip.HF20181115;
import com.softwareverde.bitcoin.secp256k1.Schnorr;
import com.softwareverde.bitcoin.secp256k1.Secp256k1;
import com.softwareverde.bitcoin.secp256k1.key.PublicKey;
import com.softwareverde.bitcoin.secp256k1.signature.Signature;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.transaction.script.Script;
import com.softwareverde.bitcoin.transaction.script.runner.ControlState;
import com.softwareverde.bitcoin.transaction.script.runner.context.Context;
import com.softwareverde.bitcoin.transaction.script.runner.context.MutableContext;
import com.softwareverde.bitcoin.transaction.script.signature.ScriptSignature;
import com.softwareverde.bitcoin.transaction.script.signature.hashtype.HashType;
import com.softwareverde.bitcoin.transaction.script.stack.Stack;
import com.softwareverde.bitcoin.transaction.script.stack.Value;
import com.softwareverde.bitcoin.transaction.signer.SignatureContext;
import com.softwareverde.bitcoin.transaction.signer.TransactionSigner;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.bytearray.MutableByteArray;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.util.bytearray.ByteArrayReader;

public class CryptographicOperation extends SubTypedOperation {
    public static final Type TYPE = Type.OP_CRYPTOGRAPHIC;

    protected static CryptographicOperation fromBytes(final ByteArrayReader byteArrayReader) {
        if (! byteArrayReader.hasBytes()) { return null; }

        final byte opcodeByte = byteArrayReader.readByte();
        final Type type = Type.getType(opcodeByte);
        if (type != TYPE) { return null; }

        final Opcode opcode = TYPE.getSubtype(opcodeByte);
        if (opcode == null) { return null; }

        return new CryptographicOperation(opcodeByte, opcode);
    }

    protected CryptographicOperation(final byte value, final Opcode opcode) {
        super(value, TYPE, opcode);
    }

    protected static Boolean checkSignature(final Context context, final PublicKey publicKey, final ScriptSignature scriptSignature, final List<ByteArray> bytesToExcludeFromScript) {
        final Transaction transaction = context.getTransaction();
        final Integer transactionInputIndexBeingSigned = context.getTransactionInputIndex();
        final TransactionOutput transactionOutputBeingSpent = context.getTransactionOutput();
        final Integer codeSeparatorIndex = context.getScriptLastCodeSeparatorIndex();
        final Script currentScript = context.getCurrentScript();

        final HashType hashType = scriptSignature.getHashType();

        final Long blockHeight = context.getBlockHeight();
        if (Buip55.isEnabled(blockHeight)) {
            if (! hashType.isBitcoinCashType()) {
                return false;
            }
        }

        final TransactionSigner transactionSigner = new TransactionSigner();
        final SignatureContext signatureContext = new SignatureContext(transaction, hashType, blockHeight);
        signatureContext.setInputIndexBeingSigned(transactionInputIndexBeingSigned);
        signatureContext.setShouldSignInputScript(transactionInputIndexBeingSigned, true, transactionOutputBeingSpent);
        signatureContext.setLastCodeSeparatorIndex(transactionInputIndexBeingSigned, codeSeparatorIndex);
        signatureContext.setCurrentScript(currentScript);
        signatureContext.setBytesToExcludeFromScript(bytesToExcludeFromScript);
        return transactionSigner.isSignatureValid(signatureContext, publicKey, scriptSignature);
    }

    protected static Boolean validateStrictSignatureEncoding(final ScriptSignature scriptSignature) {
        { // Enforce SCRIPT_VERIFY_STRICTENC... (https://github.com/bitcoincashorg/bitcoincash.org/blob/master/spec/uahf-technical-spec.md) (BitcoinXT: src/script/interpreter.cpp ::IsValidSignatureEncoding)
            if (scriptSignature == null) { return false; }

            final HashType hashType = scriptSignature.getHashType();
            if (hashType == null) { return false; }

            if (hashType.getMode() == null) { return false; }
            if (! hashType.isBitcoinCashType()) { return false; }
        }

        { // Enforce LOW_S Signature Encoding... (https://github.com/bitcoin/bips/blob/master/bip-0146.mediawiki#low_s)
            final Signature signature = scriptSignature.getSignature();
            final Boolean isCanonical = signature.isCanonical();
            if (! isCanonical) { return false; }
        }

        return true;
    }

    protected Boolean _executeCheckSignature(final Stack stack, final Context context) {
        final Value publicKeyValue = stack.pop();
        final Value signatureValue = stack.pop();

        if (stack.didOverflow()) { return false; }

        final List<ByteArray> bytesToRemoveFromScript;
        { // NOTE: All instances of the signature should be purged from the signed script...
            final ImmutableListBuilder<ByteArray> signatureBytesBuilder = new ImmutableListBuilder<>(1);
            signatureBytesBuilder.add(MutableByteArray.wrap(signatureValue.getBytes()));
            bytesToRemoveFromScript = signatureBytesBuilder.build();
        }

        final Long blockHeight = context.getBlockHeight();

        final Boolean signatureIsValid;
        {
            final ScriptSignature scriptSignature = signatureValue.asScriptSignature();

            if (Buip55.isEnabled(blockHeight)) {
                final Boolean meetsStrictEncodingStandard = validateStrictSignatureEncoding(scriptSignature);
                if (! meetsStrictEncodingStandard) { return false; }
            }

            if (scriptSignature != null) {
                final PublicKey publicKey = publicKeyValue.asPublicKey();
                signatureIsValid = CryptographicOperation.checkSignature(context, publicKey, scriptSignature, bytesToRemoveFromScript);
            }
            else {
                // NOTE: An invalid scriptSignature is permitted, and just simply fails...
                //  Example Transaction: 9FB65B7304AAA77AC9580823C2C06B259CC42591E5CCE66D76A81B6F51CC5C28
                signatureIsValid = false;
            }
        }

        if (_opcode == Opcode.CHECK_SIGNATURE_THEN_VERIFY) {
            if (! signatureIsValid) { return false; }
        }
        else {
            { // Enforce NULLFAIL... (https://github.com/bitcoin/bips/blob/master/bip-0146.mediawiki#nullfail)
                if (HF20171113.isEnabled(blockHeight)) {
                    if ((! signatureIsValid) && (! signatureValue.isEmpty())) { return false; }
                }
            }

            stack.push(Value.fromBoolean(signatureIsValid));
        }

        return (! stack.didOverflow());
    }

    protected Boolean _executeCheckMultiSignature(final Stack stack, final Context context) {
        final Integer publicKeyCount;
        {
            final Value publicKeyCountValue = stack.pop();
            publicKeyCount = publicKeyCountValue.asInteger();
        }

        final List<PublicKey> publicKeys;
        {
            final ImmutableListBuilder<PublicKey> listBuilder = new ImmutableListBuilder<>();
            for (int i = 0; i < publicKeyCount; ++i) {
                final Value publicKeyValue = stack.pop();
                final PublicKey publicKey = publicKeyValue.asPublicKey();
                listBuilder.add(publicKey);
            }
            publicKeys = listBuilder.build();
        }

        final Integer signatureCount;
        {
            final Value signatureCountValue = stack.pop();
            signatureCount = signatureCountValue.asInteger();
        }

        final boolean allSignaturesWereEmpty;
        final List<ByteArray> bytesToRemoveFromScript;
        final List<ScriptSignature> signatures;
        {
            boolean signaturesAreEmpty = true;
            final ImmutableListBuilder<ByteArray> signatureBytesBuilder = new ImmutableListBuilder<>(signatureCount);
            final ImmutableListBuilder<ScriptSignature> listBuilder = new ImmutableListBuilder<>(signatureCount);
            for (int i = 0; i < signatureCount; ++i) {
                final Value signatureValue = stack.pop();

                if (! signatureValue.isEmpty()) {
                    signaturesAreEmpty = false;
                }

                final ScriptSignature scriptSignature = signatureValue.asScriptSignature();
                // if (scriptSignature == null) { return false; } // NOTE: An invalid scriptSignature is permitted, and just simply fails / pushes a false value...
                if (scriptSignature != null) {
                    // Schnorr signatures are currently disabled for OP_CHECKMULTISIG...
                    final Signature signature = scriptSignature.getSignature();
                    if (signature.getType() == Signature.Type.SCHNORR) {
                        return false;
                    }
                }

                signatureBytesBuilder.add(MutableByteArray.wrap(signatureValue.getBytes())); // NOTE: All instances of the signature should be purged from the signed script...
                listBuilder.add(scriptSignature);
            }
            signatures = listBuilder.build();
            bytesToRemoveFromScript = signatureBytesBuilder.build();
            allSignaturesWereEmpty = signaturesAreEmpty;
        }

        stack.pop(); // Pop an extra value due to bug in the protocol...

        final Long blockHeight = context.getBlockHeight();

        final boolean signaturesAreValid;
        {   // Signatures must appear in the same order as their paired public key, but the number of signatures may be less than the number of public keys.
            // Example: P1, P2, P3 <-> S2, S3
            //          P1, P2, P3 <-> S1, S3
            //          P1, P2, P3 <-> S1, S2
            //          P1, P2, P3 <-> S1, S2, S3
            boolean signaturesHaveMatchedPublicKeys = true;
            int nextPublicKeyIndex = 0;
            for (int i = 0; i < signatureCount; ++i) {
                final ScriptSignature signature = signatures.get(i);

                boolean signatureHasPublicKeyMatch = false;
                for (int j = nextPublicKeyIndex; j < publicKeyCount; ++j) {
                    nextPublicKeyIndex += 1;

                    final PublicKey publicKey = publicKeys.get(j);
                    final boolean signatureIsValid;
                    {
                        if (Buip55.isEnabled(blockHeight)) {
                            final Boolean meetsStrictEncodingStandard = validateStrictSignatureEncoding(signature);
                            if (! meetsStrictEncodingStandard) { return false; }
                        }

                        if (signature != null) {
                            signatureIsValid = CryptographicOperation.checkSignature(context, publicKey, signature, bytesToRemoveFromScript);
                        }
                        else {
                            signatureIsValid = false; // NOTE: An invalid scriptSignature is permitted, and just simply fails...
                        }
                    }
                    if (signatureIsValid) {
                        signatureHasPublicKeyMatch = true;
                        break;
                    }
                }

                if (! signatureHasPublicKeyMatch) {
                    signaturesHaveMatchedPublicKeys = false;
                    break;
                }
            }

            signaturesAreValid = signaturesHaveMatchedPublicKeys;
        }

        if (_opcode == Opcode.CHECK_MULTISIGNATURE_THEN_VERIFY) {
            if (! signaturesAreValid) { return false; }
        }
        else {
            { // Enforce NULLFAIL... (https://github.com/bitcoin/bips/blob/master/bip-0146.mediawiki#nullfail)
                if (HF20171113.isEnabled(blockHeight)) {
                    if ((! signaturesAreValid) && (! allSignaturesWereEmpty)) { return false; }
                }
            }

            stack.push(Value.fromBoolean(signaturesAreValid));
            // stack.push(Value.fromBoolean(true));
        }

        return (! stack.didOverflow());
    }

    protected Boolean _executeCheckDataSignature(final Stack stack) {
        final Value publicKeyValue = stack.pop();
        final Value messageValue = stack.pop();
        final Value signatureValue = stack.pop();

        final ScriptSignature scriptSignature = signatureValue.asScriptSignature();
        final byte[] messageHash = BitcoinUtil.sha256(messageValue.getBytes());

        final Boolean signatureIsValid;
        if (scriptSignature != null) {
            final PublicKey publicKey = publicKeyValue.asPublicKey();
            if (publicKey == null) { return false; } // The PublicKey must be a valid for OP_CHECKDATASIG...

            final Signature signature = scriptSignature.getSignature();

            if (signature.getType() == Signature.Type.SCHNORR) {
                signatureIsValid = Schnorr.verifySignature(signature, publicKey, messageHash);
            }
            else {
                signatureIsValid = Secp256k1.verifySignature(signature, publicKey, messageHash);
            }
        }
        else {
            signatureIsValid = false;
        }

        if ((! signatureIsValid) && (! signatureValue.isEmpty())) { return false; } // Enforce NULLFAIL...

        if (_opcode == Opcode.CHECK_DATA_SIGNATURE_THEN_VERIFY) {
            if (! signatureIsValid) { return false; }
        }
        else {
            stack.push(Value.fromBoolean(signatureIsValid));
        }

        return (! stack.didOverflow());
    }

    @Override
    public Boolean applyTo(final Stack stack, final ControlState controlState, final MutableContext context) {
        switch (_opcode) {
            case RIPEMD_160: {
                final Value input = stack.pop();
                final byte[] bytes = BitcoinUtil.ripemd160(input.getBytes());
                stack.push(Value.fromBytes(bytes));

                return (! stack.didOverflow());
            }

            case SHA_1: {
                final Value input = stack.pop();
                final byte[] bytes = BitcoinUtil.sha1(input.getBytes());
                stack.push(Value.fromBytes(bytes));

                return (! stack.didOverflow());
            }

            case SHA_256: {
                final Value input = stack.pop();
                final byte[] bytes = BitcoinUtil.sha256(input.getBytes());
                stack.push(Value.fromBytes(bytes));

                return (! stack.didOverflow());
            }

            case SHA_256_THEN_RIPEMD_160: {
                final Value input = stack.pop();
                final byte[] bytes = BitcoinUtil.ripemd160(BitcoinUtil.sha256(input.getBytes()));
                stack.push(Value.fromBytes(bytes));

                return (! stack.didOverflow());
            }

            case DOUBLE_SHA_256: {
                final Value input = stack.pop();
                final byte[] bytes = BitcoinUtil.sha256(BitcoinUtil.sha256(input.getBytes()));
                stack.push(Value.fromBytes(bytes));

                return (! stack.didOverflow());
            }

            case CODE_SEPARATOR: {
                final Integer postCodeSeparatorScriptIndex = context.getScriptIndex(); // NOTE: Context.CurrentLockingScriptIndex has already been incremented. So this value is one-past the current opcode index.
                context.setCurrentScriptLastCodeSeparatorIndex(postCodeSeparatorScriptIndex);
                return true;
            }

            case CHECK_SIGNATURE:
            case CHECK_SIGNATURE_THEN_VERIFY:{
                return _executeCheckSignature(stack, context);
            }

            case CHECK_MULTISIGNATURE:
            case CHECK_MULTISIGNATURE_THEN_VERIFY: {
                return _executeCheckMultiSignature(stack, context);
            }

            case CHECK_DATA_SIGNATURE:
            case CHECK_DATA_SIGNATURE_THEN_VERIFY: {
                if (! HF20181115.isEnabled(context.getBlockHeight())) { return false; }

                return _executeCheckDataSignature(stack);
            }

            default: { return false; }
        }
    }
}
