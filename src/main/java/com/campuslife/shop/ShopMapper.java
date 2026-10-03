package com.campuslife.shop;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShopMapper {
    String COLUMNS = "id, merchant_id, name, campus, category, average_price, status, version, "
            + "description, address, updated_at";
    String FILTERS = """
            WHERE status = 1
            <if test="filter.campus != null">AND campus = #{filter.campus}</if>
            <if test="filter.category != null">AND category = #{filter.category}</if>
            <if test="filter.maxPrice != null">AND average_price &lt;= #{filter.maxPrice}</if>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM shops " + FILTERS
            + " ORDER BY id ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<ShopRow> list(@Param("filter") ShopFilter filter, @Param("offset") long offset,
                       @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM shops " + FILTERS + "</script>")
    long count(@Param("filter") ShopFilter filter);

    @Select("SELECT " + COLUMNS + " FROM shops WHERE merchant_id = #{merchantId}"
            + " ORDER BY id ASC LIMIT #{size} OFFSET #{offset}")
    List<ShopRow> listForMerchant(@Param("merchantId") long merchantId,
                                 @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM shops WHERE merchant_id = #{merchantId}")
    long countForMerchant(@Param("merchantId") long merchantId);

    @Select("SELECT " + COLUMNS + " FROM shops WHERE id = #{id} AND status = 1")
    ShopRow findOnline(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM shops WHERE id = #{id}")
    ShopRow findById(@Param("id") long id);

    @Update("""
            <script>
            UPDATE shops
            <set>
                <if test="name != null">name = #{name},</if>
                <if test="averagePrice != null">average_price = #{averagePrice},</if>
                <if test="status != null">status = #{status},</if>
                version = version + 1, updated_at = #{updatedAt}
            </set>
            WHERE id = #{id} AND merchant_id = #{merchantId} AND version = #{version}
            </script>
            """)
    int update(@Param("id") long id, @Param("merchantId") long merchantId,
               @Param("name") String name, @Param("averagePrice") Integer averagePrice,
               @Param("status") Integer status, @Param("version") int version,
               @Param("updatedAt") LocalDateTime updatedAt);
}
