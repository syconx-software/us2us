CREATE TABLE `communities` (
	`id` binary(16) NOT NULL,
	`owner` binary(16) NOT NULL,
	`visibility` tinyint unsigned NOT NULL,
	`name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
	`search_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
	`revision` bigint unsigned NOT NULL,
	`state` blob NOT NULL,
	`signature` binary(64) NOT NULL,
	CONSTRAINT `communities_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `community_envelopes` (
	`id` binary(16) NOT NULL,
	`community_id` binary(16) NOT NULL,
	`sender` binary(16) NOT NULL,
	`recipient` binary(16) NOT NULL,
	`message_id` binary(16) NOT NULL,
	`revision` bigint unsigned NOT NULL,
	`server_ts` bigint unsigned NOT NULL,
	`digest` binary(32) NOT NULL,
	`size` int unsigned NOT NULL,
	`body` mediumblob,
	CONSTRAINT `community_envelopes_id` PRIMARY KEY(`id`),
	CONSTRAINT `community_delivery` UNIQUE(`community_id`,`sender`,`recipient`,`message_id`)
);
--> statement-breakpoint
CREATE TABLE `community_invites` (
	`token_hash` binary(32) NOT NULL,
	`community_id` binary(16) NOT NULL,
	`expires_at` bigint unsigned NOT NULL,
	`redeemed_by` binary(16),
	CONSTRAINT `community_invites_token_hash` PRIMARY KEY(`token_hash`)
);
--> statement-breakpoint
CREATE TABLE `community_members` (
	`community_id` binary(16) NOT NULL,
	`account_id` binary(16) NOT NULL,
	CONSTRAINT `community_members_community_id_account_id_pk` PRIMARY KEY(`community_id`,`account_id`)
);
--> statement-breakpoint
CREATE TABLE `community_requests` (
	`community_id` binary(16) NOT NULL,
	`account_id` binary(16) NOT NULL,
	`created_at` bigint unsigned NOT NULL,
	CONSTRAINT `community_requests_community_id_account_id_pk` PRIMARY KEY(`community_id`,`account_id`)
);
--> statement-breakpoint
ALTER TABLE `communities` ADD CONSTRAINT `communities_owner_accounts_id_fk` FOREIGN KEY (`owner`) REFERENCES `accounts`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_envelopes` ADD CONSTRAINT `community_envelopes_community_id_communities_id_fk` FOREIGN KEY (`community_id`) REFERENCES `communities`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_envelopes` ADD CONSTRAINT `community_envelopes_sender_accounts_id_fk` FOREIGN KEY (`sender`) REFERENCES `accounts`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_envelopes` ADD CONSTRAINT `community_envelopes_recipient_accounts_id_fk` FOREIGN KEY (`recipient`) REFERENCES `accounts`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_invites` ADD CONSTRAINT `community_invites_community_id_communities_id_fk` FOREIGN KEY (`community_id`) REFERENCES `communities`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_members` ADD CONSTRAINT `community_members_community_id_communities_id_fk` FOREIGN KEY (`community_id`) REFERENCES `communities`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_members` ADD CONSTRAINT `community_members_account_id_accounts_id_fk` FOREIGN KEY (`account_id`) REFERENCES `accounts`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_requests` ADD CONSTRAINT `community_requests_community_id_communities_id_fk` FOREIGN KEY (`community_id`) REFERENCES `communities`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `community_requests` ADD CONSTRAINT `community_requests_account_id_accounts_id_fk` FOREIGN KEY (`account_id`) REFERENCES `accounts`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX `communities_directory` ON `communities` (`visibility`,`search_name`);--> statement-breakpoint
CREATE INDEX `communities_owner` ON `communities` (`owner`);--> statement-breakpoint
CREATE INDEX `community_inbox` ON `community_envelopes` (`recipient`,`server_ts`,`size`);--> statement-breakpoint
CREATE INDEX `community_sender` ON `community_envelopes` (`sender`,`community_id`,`recipient`,`server_ts`,`size`);--> statement-breakpoint
CREATE INDEX `community_expiry` ON `community_envelopes` (`server_ts`);--> statement-breakpoint
CREATE INDEX `community_invites_group` ON `community_invites` (`community_id`);--> statement-breakpoint
CREATE INDEX `community_invites_expiry` ON `community_invites` (`expires_at`);--> statement-breakpoint
CREATE INDEX `community_members_account` ON `community_members` (`account_id`);