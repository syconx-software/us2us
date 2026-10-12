CREATE TABLE `avatars` (
	`account_id` binary(16) NOT NULL,
	`highest_version` bigint unsigned NOT NULL,
	`record` blob,
	CONSTRAINT `avatars_account_id` PRIMARY KEY(`account_id`)
);
