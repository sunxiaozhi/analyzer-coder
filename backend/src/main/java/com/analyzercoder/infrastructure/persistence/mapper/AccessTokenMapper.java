package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.AccessTokenRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccessTokenMapper {
    void insert(AccessTokenRow row);

    AccessTokenRow findByHash(@Param("hash") String hash);

    List<AccessTokenRow> list(@Param("accountId") UUID accountId);

    int revoke(@Param("accountId") UUID accountId, @Param("id") UUID id, @Param("now") Instant now);

    int touch(@Param("id") UUID id, @Param("now") Instant now);
}
