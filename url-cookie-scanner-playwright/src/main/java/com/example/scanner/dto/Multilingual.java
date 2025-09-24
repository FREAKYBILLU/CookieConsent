package com.example.scanner.dto;


import com.example.scanner.enums.LANGUAGE;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Multilingual {

    @Schema(description = "Supported languages", example = "[\"ENGLISH\", \"HINDI\"]")
    @NotEmpty(message = "JCMP1021")
    private List<LANGUAGE> supportedLanguages;

    @Schema(description = "Language specific content map")
    @NotNull(message = "JCMP1022")
    @Valid
    Map<LANGUAGE, LanguageSpecificContent> languageSpecificContentMap;
}