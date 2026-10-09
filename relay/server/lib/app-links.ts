/**
 * Universal links (docs/INVITES.md): https://us2us.app/u/<username> opens
 * the iOS app when it is installed (its Associated Domains entitlement is
 * `applinks:us2us.app`). Apple's CDN fetches this file from
 * https://us2us.app/.well-known/apple-app-site-association over HTTPS, with
 * no redirect, as `application/json`. Only invite paths are claimed; every
 * other page of the site stays in the browser.
 */

export const APP_SITE_ASSOCIATION_PATH = '/.well-known/apple-app-site-association'

/** Team id + bundle id of the iOS app (ios/App/project.yml). */
export const IOS_APP_ID = 'C8QW8MBYQX.com.syconx.us2us'

export const APP_SITE_ASSOCIATION = {
  applinks: {
    details: [
      {
        appIDs: [IOS_APP_ID],
        components: [{ '/': '/u/*', 'comment': 'Invite links: https://us2us.app/u/<username>' }],
      },
    ],
  },
} as const

/** The file's bytes, built once. */
export const APP_SITE_ASSOCIATION_JSON = `${JSON.stringify(APP_SITE_ASSOCIATION)}\n`

export const APP_SITE_ASSOCIATION_HEADERS: Readonly<Record<string, string>> = {
  'content-type': 'application/json',
  'x-content-type-options': 'nosniff',
  // Apple's CDN refreshes it on its own schedule; browsers may keep it an hour.
  'cache-control': 'public, max-age=3600',
}
