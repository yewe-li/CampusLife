package com.campuslife.voucher;

import java.time.LocalDateTime;
import java.util.List;

public final class VoucherModels {
    private VoucherModels() {}

    public record Offer(long id, long shopId, String title, int discountAmount, int minSpend,
                        LocalDateTime claimStart, LocalDateTime claimEnd, LocalDateTime useEnd,
                        int status, int shopStatus) {}

    public record PublicVoucher(long id, long shopId, String title, int discountAmount, int minSpend,
                                LocalDateTime claimStart, LocalDateTime claimEnd,
                                LocalDateTime useEnd, int stock) {}

    public record Claim(String id, long voucherId, String redeemCode, LocalDateTime expiresAt,
                        String status, LocalDateTime createdAt) {
        @Override public String toString() {
            return "Claim[id=" + id + ", voucherId=" + voucherId + ", redeemCode=REDACTED, status=" + status + "]";
        }
    }

    public record MyVoucher(String id, long voucherId, String redeemCode, LocalDateTime expiresAt,
                            String status, LocalDateTime createdAt, String voucherTitle,
                            String shopName, LocalDateTime redeemedAt) {
        @Override public String toString() {
            return "MyVoucher[id=" + id + ", voucherId=" + voucherId + ", redeemCode=REDACTED, status=" + status + "]";
        }
    }

    public record RedemptionRequest(String redeemCode) {
        @Override public String toString() { return "RedemptionRequest[redeemCode=REDACTED]"; }
    }

    public record Redemption(String id, long voucherId, long shopId, String voucherTitle,
                             String shopName, String status, LocalDateTime redeemedAt) {}

    public record RedemptionRow(String id, long voucherId, LocalDateTime expiresAt, String status) {}

    public record RedemptionShop(long shopId, long merchantId, String voucherTitle, String shopName) {}

    public record Page<T>(List<T> items, long total, int page, int size) {}
}
