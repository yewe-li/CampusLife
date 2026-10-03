package com.campuslife.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id, nickname, role FROM users WHERE phone = #{phone}")
    UserPrincipal findByPhone(@Param("phone") String phone);
}
