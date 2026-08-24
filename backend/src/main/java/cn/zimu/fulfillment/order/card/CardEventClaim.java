package cn.zimu.fulfillment.order.card;

record CardEventClaim(boolean process, String claimToken, int attempt, CardInteractionOutcome outcome) {

    static CardEventClaim claimed(String claimToken, int attempt) {
        return new CardEventClaim(true, claimToken, attempt, null);
    }

    static CardEventClaim duplicate(CardInteractionOutcome outcome) {
        return new CardEventClaim(false, null, 0, outcome);
    }
}
