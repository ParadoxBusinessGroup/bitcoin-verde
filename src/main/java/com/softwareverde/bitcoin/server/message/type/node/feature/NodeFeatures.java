package com.softwareverde.bitcoin.server.message.type.node.feature;

import com.softwareverde.util.Util;

public class NodeFeatures {
    public enum Feature {
        NONE                                            ((long) (0x00 << 0x00)),
        BLOCKCHAIN_ENABLED                              ((long) (0x01 << 0x00)),
        GETUTXO_PROTOCOL_ENABLED                        ((long) (0x01 << 0x01)),
        BLOOM_CONNECTIONS_ENABLED                       ((long) (0x01 << 0x02)),
        UNUSED                                          ((long) (0x01 << 0x03)),
        XTHIN_PROTOCOL_ENABLED                          ((long) (0x01 << 0x04)),
        BITCOIN_CASH_ENABLED                            ((long) (0x01 << 0x05));

        public final Long value;

        Feature(final Long flag) {
            this.value = flag;
        }
    }

    private Long _value;

    public NodeFeatures() {
        _value = Feature.NONE.value;
    }

    public NodeFeatures(final Long value) {
        _value = value;
    }

    public void setFeaturesFlags(final NodeFeatures nodeFeatures) {
        _value = nodeFeatures._value;
    }

    public void setFeatureFlags(final Long value) {
        _value = value;
    }

    public void enableFeatureFlag(final Long nodeFeatureFlag) {
        _value = (_value | nodeFeatureFlag);
    }

    public void enableFeature(final Feature feature) {
        _value = (_value | feature.value);
    }

    public Boolean hasFeatureFlagEnabled(final Feature feature) {
        if (Util.areEqual(Feature.NONE.value, feature.value)) {
            return (Util.areEqual(_value, Feature.NONE.value));
        }

        return ( ((int) (_value & feature.value)) > 0 );
    }

    public Long getFeatureFlags() {
        return _value;
    }

    public void clear() {
        _value = Feature.NONE.value;
    }
}
