package cn.zimu.fulfillment.common.web;

/** Server-verified authentication provenance; authorization may require a specific kind. */
public enum AuthenticationKind {
    NONE,
    SHARED_BASIC,
    GATEWAY_ASSERTION,
    INTERNAL_SERVICE
}
