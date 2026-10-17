package com.premisave.developer.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One change made through the admin API: who did what to which record. Written once, never changed. */
@Document(collection = "admin_actions")
public class AdminAction {

    @Id
    private String id;
    @Indexed
    private String actorId;
    private String actorEmail;
    /** For example DEVELOPER_DISABLED or APP_DELETED. */
    private String action;
    /** DEVELOPER, APP, KEY, BUSINESS or TRANSFER. */
    private String targetType;
    @Indexed
    private String targetId;
    /** A short plain description of what changed. Never a secret. */
    private String detail;
    @Indexed
    private Instant createdAt;

    public AdminAction() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getActorId() { return actorId; }
    public void setActorId(String v) { this.actorId = v; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String v) { this.actorEmail = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String v) { this.targetType = v; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String v) { this.targetId = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
