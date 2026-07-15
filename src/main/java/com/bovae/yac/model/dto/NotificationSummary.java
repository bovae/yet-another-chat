package com.bovae.yac.model.dto;

/**
 * Aggregate notification counts for the navbar badge (R3-10). Serialized snake_case
 * ({@code unread_total}, {@code pending_friend_requests}, {@code pending_invitations})
 * by the global naming strategy.
 */
public record NotificationSummary(int unreadTotal, int pendingFriendRequests, int pendingInvitations) {}
