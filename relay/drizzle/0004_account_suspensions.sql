CREATE TABLE `suspended_accounts` (
	`account_id` binary(16) NOT NULL,
	`suspended_at` bigint unsigned NOT NULL,
	`reason` varchar(32) NOT NULL,
	`applied_at` bigint unsigned,
	CONSTRAINT `suspended_accounts_account_id` PRIMARY KEY(`account_id`)
);
--> statement-breakpoint
ALTER TABLE `suspended_accounts` ADD CONSTRAINT `suspended_accounts_account_id_accounts_id_fk` FOREIGN KEY (`account_id`) REFERENCES `accounts`(`id`) ON DELETE cascade ON UPDATE no action;