CREATE TABLE `accounts` (
	`id` binary(16) NOT NULL,
	`identity_key` binary(33) NOT NULL,
	`registration_id` smallint unsigned NOT NULL,
	`auth_token_hash` binary(32) NOT NULL,
	`access_key_hash` binary(32),
	`signed_pre_key_id` int unsigned NOT NULL,
	`signed_pre_key` binary(33) NOT NULL,
	`signed_pre_key_signature` binary(64) NOT NULL,
	`kyber_last_resort_key_id` int unsigned NOT NULL,
	`kyber_last_resort_key` varbinary(2048) NOT NULL,
	`kyber_last_resort_signature` binary(64) NOT NULL,
	`last_seen` date NOT NULL,
	CONSTRAINT `accounts_id` PRIMARY KEY(`id`),
	CONSTRAINT `accounts_auth_token_hash` UNIQUE(`auth_token_hash`)
);
--> statement-breakpoint
CREATE TABLE `envelopes` (
	`recipient` binary(16) NOT NULL,
	`id` binary(16) NOT NULL,
	`server_ts` bigint unsigned NOT NULL,
	`body` mediumblob NOT NULL,
	CONSTRAINT `envelopes_pk` PRIMARY KEY(`recipient`,`id`)
);
--> statement-breakpoint
CREATE TABLE `media` (
	`id` binary(16) NOT NULL,
	`uploader` binary(16) NOT NULL,
	`delete_token_hash` binary(32) NOT NULL,
	`size` bigint unsigned NOT NULL,
	`expires_at` bigint unsigned NOT NULL,
	CONSTRAINT `media_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `one_time_pre_keys` (
	`account_id` binary(16) NOT NULL,
	`kind` tinyint unsigned NOT NULL,
	`key_id` int unsigned NOT NULL,
	`public_key` varbinary(2048) NOT NULL,
	`signature` binary(64),
	CONSTRAINT `one_time_pre_keys_pk` PRIMARY KEY(`account_id`,`kind`,`key_id`)
);
--> statement-breakpoint
CREATE TABLE `used_pow_tokens` (
	`token_hash` binary(32) NOT NULL,
	`expires_at` bigint unsigned NOT NULL,
	CONSTRAINT `used_pow_tokens_token_hash` PRIMARY KEY(`token_hash`)
);
--> statement-breakpoint
CREATE TABLE `usernames` (
	`username_hash` binary(32) NOT NULL,
	`account_id` binary(16) NOT NULL,
	`held_until` bigint unsigned,
	CONSTRAINT `usernames_username_hash` PRIMARY KEY(`username_hash`)
);
--> statement-breakpoint
CREATE INDEX `accounts_last_seen` ON `accounts` (`last_seen`);--> statement-breakpoint
CREATE INDEX `envelopes_server_ts` ON `envelopes` (`server_ts`);--> statement-breakpoint
CREATE INDEX `media_uploader` ON `media` (`uploader`);--> statement-breakpoint
CREATE INDEX `media_expires_at` ON `media` (`expires_at`);--> statement-breakpoint
CREATE INDEX `used_pow_tokens_expires_at` ON `used_pow_tokens` (`expires_at`);--> statement-breakpoint
CREATE INDEX `usernames_account` ON `usernames` (`account_id`);--> statement-breakpoint
CREATE INDEX `usernames_held_until` ON `usernames` (`held_until`);