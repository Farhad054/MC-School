/**
 * Shows a freshly created account's invitation token. Until invitation emails are
 * wired up (PRD 4.6), the admin/teacher copies this link and gives it to the invitee.
 */
export function InvitationNotice({ message, token }: { message: string; token: string }) {
  const link = `${window.location.origin}/activate?token=${encodeURIComponent(token)}`;
  return (
    <div className="banner banner--success" style={{ marginTop: 16 }}>
      <div>{message}</div>
      <div className="token-box">{link}</div>
    </div>
  );
}
