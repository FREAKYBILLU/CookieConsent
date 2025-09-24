package com.example.scanner.mapper;

import com.example.scanner.dto.CookieDto;
import com.example.scanner.dto.ScanResultDto;
import com.example.scanner.entity.CookieEntity;
import com.example.scanner.entity.ScanResultEntity;
import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.Source;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ScanResultMapper {

    public static CookieEntity cookieDtoToEntity(CookieDto dto) {
        return new CookieEntity(
                dto.getName(),
                dto.getUrl(),
                dto.getDomain(),
                dto.getPath(),
                dto.getExpires(),
                dto.isSecure(),
                dto.isHttpOnly(),
                dto.getSameSite() != null
                        ? SameSite.valueOf(dto.getSameSite().name())
                        : null,
                dto.getSource() != null
                        ? Source.valueOf(dto.getSource().name())
                        : null,
                dto.getCategory(),
                dto.getDescription(),
                dto.getDescription_gpt(),
                dto.getSubdomainName() != null ? dto.getSubdomainName() : "main",
                dto.getPrivacyPolicyUrl(),
                dto.getProvider()
        );
    }

    public static CookieDto cookieEntityToDto(CookieEntity entity) {
        return new CookieDto(
                entity.getName(),
                entity.getUrl(),
                entity.getDomain(),
                entity.getPath(),
                entity.getExpires(),
                entity.isSecure(),
                entity.isHttpOnly(),
                entity.getSameSite(),
                entity.getSource(),
                entity.getCategory(),
                entity.getDescription(),
                entity.getDescription_gpt(),
                entity.getSubdomainName() != null ? entity.getSubdomainName() : "main",
                entity.getPrivacyPolicyUrl(),
                entity.getProvider()
        );
    }
}