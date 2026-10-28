/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.groups;

import android.content.Context;
import org.signal.core.models.ServiceId.ACI;
import org.signal.libsignal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import java.util.Collections;

/** Gateway-only mutations: no automatic bans or unbans, including after conflict retries. */
public final class GatewayGroupActions {
  private GatewayGroupActions() {}

  public static GroupManager.GroupActionResult apply(Context context, GroupId.V2 groupId, Recipient applicant, String operation, Runnable precondition) throws Exception {
    precondition.run();
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId)) {
      editor.gatewayPrecondition = precondition;
      switch (operation) {
        case "ADD":
          var current = SignalDatabase.groups().requireGroup(groupId).requireV2GroupProperties().getDecryptedGroup();
          if (current.members.stream().anyMatch(m -> m.aciBytes.equals(applicant.requireAci().toByteString())) ||
              current.pendingMembers.stream().anyMatch(m -> m.serviceIdBytes.equals(applicant.requireAci().toByteString()))) return null;
          return editor.addMembers(Collections.singleton(applicant.getId()));
        case "APPROVE":
          return editor.approveRequests(Collections.singleton(applicant.getId()));
        case "DENY":
          editor.denyRequests(Collections.singleton(applicant.getId()), false);
          break;
        case "REMOVE":
          editor.ejectMember(applicant.requireAci(), false, false, true);
          break;
        case "CLEANUP":
          var group = SignalDatabase.groups().requireGroup(groupId).requireV2GroupProperties().getDecryptedGroup();
          boolean member = group.members.stream().anyMatch(m -> m.aciBytes.equals(applicant.requireAci().toByteString()));
          if (member) {
            editor.ejectMember(applicant.requireAci(), false, false, true);
          } else {
            for (var pending : group.pendingMembers) {
              if (pending.serviceIdBytes.equals(applicant.requireAci().toByteString())) {
                editor.revokeInvites(Recipient.self().requireAci(), Collections.singleton(new UuidCiphertext(pending.serviceIdCipherText.toByteArray())), true);
              }
            }
          }
          break;
        default: throw new IllegalArgumentException("Unknown Gateway operation");
      }
      return null;
    }
  }
}
