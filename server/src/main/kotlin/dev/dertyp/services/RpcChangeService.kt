package dev.dertyp.services

import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.Change
import dev.dertyp.data.User
import kotlinx.coroutines.flow.Flow

class RpcChangeService(
    private val user: User,
    private val notifier: ChangeNotifier
) : IChangeService {
    override fun observeChanges(): Flow<Change> = notifier.observe(user.id)
}
