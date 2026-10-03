package com.campuslife.voucher;

import com.campuslife.voucher.VoucherModels.Claim;
import com.campuslife.voucher.VoucherModels.MyVoucher;
import com.campuslife.voucher.VoucherModels.Offer;
import com.campuslife.voucher.VoucherModels.PublicVoucher;
import com.campuslife.voucher.VoucherModels.RedemptionRow;
import com.campuslife.voucher.VoucherModels.RedemptionShop;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VoucherMapper {
    @Select("SELECT COUNT(*) FROM shops WHERE id = #{shopId} AND status = 1")
    int countOnlineShop(@Param("shopId") long shopId);

    @Select("""
            SELECT v.id, v.shop_id, v.title, v.discount_amount, v.min_spend,
                   v.claim_start, v.claim_end, v.use_end, v.status, s.status AS shop_status
            FROM vouchers v JOIN shops s ON s.id = v.shop_id
            WHERE v.id = #{voucherId}
            """)
    Offer findOffer(@Param("voucherId") long voucherId);

    @Select("""
            SELECT COUNT(*) FROM vouchers v
            JOIN shops s ON s.id = v.shop_id
            JOIN voucher_stock vs ON vs.voucher_id = v.id
            WHERE v.shop_id = #{shopId} AND s.status = 1 AND v.status = 1
              AND v.claim_start <= #{now} AND v.claim_end > #{now} AND v.use_end > #{now}
            """)
    long countPublicVouchers(@Param("shopId") long shopId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT v.id, v.shop_id, v.title, v.discount_amount, v.min_spend,
                   v.claim_start, v.claim_end, v.use_end, vs.stock
            FROM vouchers v JOIN shops s ON s.id = v.shop_id
            JOIN voucher_stock vs ON vs.voucher_id = v.id
            WHERE v.shop_id = #{shopId} AND s.status = 1 AND v.status = 1
              AND v.claim_start <= #{now} AND v.claim_end > #{now} AND v.use_end > #{now}
            ORDER BY v.claim_end ASC, v.id ASC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<PublicVoucher> findPublicVouchers(@Param("shopId") long shopId,
                                          @Param("now") LocalDateTime now,
                                          @Param("offset") long offset, @Param("size") int size);

    @Select("""
            SELECT COUNT(*) FROM voucher_orders
            WHERE user_id = #{userId} AND voucher_id = #{voucherId}
            """)
    int countClaim(@Param("userId") long userId, @Param("voucherId") long voucherId);

    @Update("""
            UPDATE voucher_stock SET stock = stock - 1
            WHERE voucher_id = #{voucherId} AND stock > 0
            """)
    int decrementStock(@Param("voucherId") long voucherId);

    @Insert("""
            INSERT INTO voucher_orders
                (id, user_id, voucher_id, redeem_code, expires_at, status, created_at)
            VALUES (#{claim.id}, #{userId}, #{claim.voucherId}, #{claim.redeemCode},
                    #{claim.expiresAt}, #{claim.status}, #{claim.createdAt})
            """)
    int insertClaim(@Param("userId") long userId, @Param("claim") Claim claim);

    @Select("SELECT COUNT(*) FROM voucher_orders WHERE user_id = #{userId}")
    long countMyVouchers(@Param("userId") long userId);

    @Select("""
            SELECT vo.id, vo.voucher_id, vo.redeem_code, vo.expires_at, vo.status,
                   vo.created_at, v.title AS voucher_title, s.name AS shop_name, vo.redeemed_at
            FROM voucher_orders vo
            JOIN vouchers v ON v.id = vo.voucher_id
            JOIN shops s ON s.id = v.shop_id
            WHERE vo.user_id = #{userId}
            ORDER BY vo.created_at DESC, vo.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<MyVoucher> findMyVouchers(@Param("userId") long userId, @Param("offset") long offset,
                                  @Param("size") int size);

    @Select("""
            SELECT id, voucher_id, expires_at, status
            FROM voucher_orders WHERE redeem_code = #{redeemCode}
            FOR UPDATE
            """)
    RedemptionRow lockForRedemption(@Param("redeemCode") String redeemCode);

    @Select("""
            SELECT s.id AS shop_id, s.merchant_id, v.title AS voucher_title, s.name AS shop_name
            FROM vouchers v JOIN shops s ON s.id = v.shop_id
            WHERE v.id = #{voucherId}
            """)
    RedemptionShop findRedemptionShop(@Param("voucherId") long voucherId);

    @Update("""
            UPDATE voucher_orders vo
            SET status = 'REDEEMED', redeemed_at = #{now}, redeemed_by = #{merchantId}
            WHERE vo.id = #{id} AND vo.status = 'ISSUED' AND vo.expires_at > #{now}
              AND EXISTS (SELECT 1 FROM vouchers v JOIN shops s ON s.id = v.shop_id
                          WHERE v.id = vo.voucher_id AND s.merchant_id = #{merchantId})
            """)
    int redeem(@Param("id") String id, @Param("merchantId") long merchantId,
               @Param("now") LocalDateTime now);
}
