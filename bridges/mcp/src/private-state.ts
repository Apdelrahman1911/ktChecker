/** POSIX owner/mode checks do not validate Windows ACLs. Until the fixed-purpose
 * ACL controller is implemented and tested, private state must fail closed there.
 * A platform-neutral protocol test is not qualification of credential isolation.
 */
export function assertPrivateStatePlatform(platform: NodeJS.Platform = process.platform): void {
  if (platform === 'win32') throw new Error('private_state_platform_unqualified');
}
