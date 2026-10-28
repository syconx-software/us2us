ALTER TABLE `accounts` ADD `created_on` date DEFAULT '1970-01-01' NOT NULL;--> statement-breakpoint
ALTER TABLE `envelopes` ADD `via_pow` tinyint unsigned DEFAULT 0 NOT NULL;--> statement-breakpoint
CREATE INDEX `community_tombstones` ON `community_envelopes` (`size`,`server_ts`);--> statement-breakpoint
CREATE INDEX `community_requests_account` ON `community_requests` (`account_id`,`created_at`);--> statement-breakpoint
CREATE INDEX `community_requests_created` ON `community_requests` (`created_at`);--> statement-breakpoint
ALTER TABLE `community_envelopes` DROP INDEX `community_inbox`, ADD INDEX `community_inbox` (`recipient`,`size`,`server_ts`,`community_id`,`sender`);