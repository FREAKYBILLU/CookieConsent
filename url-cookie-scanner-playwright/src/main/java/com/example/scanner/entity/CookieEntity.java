package com.example.scanner.entity;

import com.example.scanner.enums.SameSite;
import com.example.scanner.enums.Source;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
public class CookieEntity {
  private String name;
  private String url;
  private String domain;
  private String path;
  private Instant expires;
  private boolean secure;
  private boolean httpOnly;
  private SameSite sameSite;
  private Source source;
  private String category;     // New field for cookie category
  private String description;// New field for cookie description
  private String description_gpt;// New field for cookie description_gpt

  public CookieEntity() {
  }

  // Constructor with new fields
  public CookieEntity(String name, String url, String domain, String path, Instant expires,
                      boolean secure, boolean httpOnly, SameSite sameSite, Source source,
                      String category, String description, String description_gpt) {
    this.name = name;
    this.url = url;
    this.domain = domain;
    this.path = path;
    this.expires = expires;
    this.secure = secure;
    this.httpOnly = httpOnly;
    this.sameSite = sameSite;
    this.source = source;
    this.category = category;
    this.description = description;
    this.description_gpt = description_gpt;
  }


}