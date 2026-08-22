package dev.charanjeev.bahi.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionSource
import dev.charanjeev.bahi.feature.transactions.navigation.TransactionIdArg
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transactionId: String? = savedStateHandle[TransactionIdArg]
    private val mode = if (transactionId == null) FormMode.ADD else FormMode.EDIT

    // Fields this form never shows -- merchant, source, id, createdAt -- kept
    // here so saving an edit never clobbers data the screen never displayed.
    // Null in ADD mode: there's nothing to preserve until the first save.
    private var original: Transaction? = null

    private val amountText = savedStateHandle.getStateFlow(KEY_AMOUNT_TEXT, "")
    private val typeName = savedStateHandle.getStateFlow(KEY_TYPE, TransactionType.EXPENSE.name)
    private val dateEpochDays = savedStateHandle.getStateFlow(
        KEY_DATE_EPOCH_DAYS,
        clock.todayIn(TimeZone.currentSystemDefault()).toEpochDays(),
    )
    private val description = savedStateHandle.getStateFlow(KEY_DESCRIPTION, "")
    private val categoryId = savedStateHandle.getStateFlow<String?>(KEY_CATEGORY_ID, null)
    private val categoryLockedByUser = savedStateHandle.getStateFlow(KEY_CATEGORY_LOCKED, false)
    private val notes = savedStateHandle.getStateFlow(KEY_NOTES, "")
    private val isDirty = savedStateHandle.getStateFlow(KEY_DIRTY, false)
    private val submitAttempted = savedStateHandle.getStateFlow(KEY_SUBMIT_ATTEMPTED, false)
    // getStateFlow(key, default) writes its default into the handle the
    // instant it's called, so savedStateHandle.contains(KEY_AMOUNT_TEXT) is
    // true from the field initializers above onward -- it can never tell a
    // fresh load apart from a process-death restore. This dedicated flag can.
    private val hasSeededFromRepository = savedStateHandle.getStateFlow(KEY_SEEDED, false)

    private val categories = MutableStateFlow(persistentListOf<Category>())
    private val isSaving = MutableStateFlow(false)
    private val showDiscardConfirmation = MutableStateFlow(false)
    private val loadError = MutableStateFlow<String?>(null)
    // ADD has nothing to fetch, so it starts already loaded.
    private val loaded = MutableStateFlow(mode == FormMode.ADD)

    private val eventChannel = Channel<TransactionFormEvent>(Channel.BUFFERED)
    val events: Flow<TransactionFormEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            categoryRepository.observeCategories().collect { categories.value = it.toPersistentList() }
        }
        if (mode == FormMode.EDIT) {
            viewModelScope.launch {
                val transaction = transactionRepository.observeTransaction(transactionId!!).first()
                if (transaction == null) {
                    loadError.value = "This transaction no longer exists."
                    return@launch
                }
                original = transaction
                // A process-death restore already seeded these once -- don't
                // clobber an in-progress (possibly dirty) edit with the
                // values the transaction had before the user touched it.
                if (!hasSeededFromRepository.value) {
                    val decimalPlaces = decimalPlacesFor(transaction.currencyCode)
                    savedStateHandle[KEY_AMOUNT_TEXT] = formatAmountForEditing(transaction.amount.absolute, decimalPlaces)
                    savedStateHandle[KEY_TYPE] =
                        if (transaction.isExpense) TransactionType.EXPENSE.name else TransactionType.INCOME.name
                    savedStateHandle[KEY_DATE_EPOCH_DAYS] = transaction.date.toEpochDays()
                    savedStateHandle[KEY_DESCRIPTION] = transaction.description
                    savedStateHandle[KEY_CATEGORY_ID] = transaction.categoryId
                    savedStateHandle[KEY_CATEGORY_LOCKED] = transaction.categoryLockedByUser
                    savedStateHandle[KEY_NOTES] = transaction.notes.orEmpty()
                    savedStateHandle[KEY_SEEDED] = true
                }
                loaded.value = true
            }
        }
    }

    private val editableFields = combine(
        amountText, typeName, dateEpochDays, description, categoryId,
    ) { amount, type, epochDays, desc, catId ->
        EditableFields(amount, TransactionType.valueOf(type), LocalDate.fromEpochDays(epochDays.toInt()), desc, catId)
    }

    private val metaFields = combine(
        categoryLockedByUser, notes, isDirty, submitAttempted, isSaving,
    ) { locked, notesValue, dirty, attempted, saving ->
        MetaFields(locked, notesValue, dirty, attempted, saving)
    }

    private val loadState = combine(loaded, loadError) { isLoaded, error -> LoadState(isLoaded, error) }

    val uiState: StateFlow<TransactionFormUiState> = combine(
        editableFields, metaFields, categories, showDiscardConfirmation, loadState,
    ) { editable, meta, cats, showDiscard, load ->
        when {
            load.error != null -> TransactionFormUiState.Error(load.error)
            !load.isLoaded -> TransactionFormUiState.Loading
            else -> TransactionFormUiState.Editing(
                mode = mode,
                amountText = editable.amountText,
                type = editable.type,
                date = editable.date,
                description = editable.description,
                categoryId = editable.categoryId,
                categoryLockedByUser = meta.categoryLockedByUser,
                notes = meta.notes,
                categories = cats,
                currencyCode = original?.currencyCode ?: DEFAULT_CURRENCY_CODE,
                submitAttempted = meta.submitAttempted,
                isDirty = meta.isDirty,
                isSaving = meta.isSaving,
                showDiscardConfirmation = showDiscard,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionFormUiState.Loading,
    )

    fun onAmountTextChange(raw: String) {
        savedStateHandle[KEY_AMOUNT_TEXT] = sanitizeAmountInput(raw)
        markDirty()
    }

    /** Canonicalizes the text once the user leaves the field -- never while they're still typing. */
    fun onAmountFieldFocusLost() {
        val decimalPlaces = decimalPlacesFor(original?.currencyCode ?: DEFAULT_CURRENCY_CODE)
        val magnitude = parseAmountMagnitude(amountText.value, decimalPlaces) ?: return
        savedStateHandle[KEY_AMOUNT_TEXT] = formatAmountForEditing(magnitude, decimalPlaces)
    }

    fun onTypeChange(type: TransactionType) {
        savedStateHandle[KEY_TYPE] = type.name
        markDirty()
    }

    fun onDateChange(date: LocalDate) {
        savedStateHandle[KEY_DATE_EPOCH_DAYS] = date.toEpochDays()
        markDirty()
    }

    fun onDescriptionChange(value: String) {
        savedStateHandle[KEY_DESCRIPTION] = value
        markDirty()
    }

    fun onCategorySelected(id: String) {
        savedStateHandle[KEY_CATEGORY_ID] = id
        savedStateHandle[KEY_CATEGORY_LOCKED] = true
        markDirty()
    }

    fun onNotesChange(value: String) {
        savedStateHandle[KEY_NOTES] = value
        markDirty()
    }

    fun onSave() {
        savedStateHandle[KEY_SUBMIT_ATTEMPTED] = true
        val state = uiState.value as? TransactionFormUiState.Editing ?: return
        if (state.hasErrors) return

        isSaving.value = true
        viewModelScope.launch {
            val decimalPlaces = decimalPlacesFor(state.currencyCode)
            val magnitude = parseAmountMagnitude(state.amountText, decimalPlaces)!!
            val signedAmount = if (state.type == TransactionType.INCOME) magnitude else -magnitude
            val now = clock.now()
            val transaction = Transaction(
                id = original?.id ?: UUID.randomUUID().toString(),
                amount = signedAmount,
                currencyCode = state.currencyCode,
                date = state.date,
                description = state.description.trim(),
                merchant = original?.merchant,
                categoryId = state.categoryId,
                accountId = original?.accountId ?: DEFAULT_ACCOUNT_ID,
                source = original?.source ?: TransactionSource.MANUAL,
                notes = state.notes.trim().ifEmpty { null },
                categoryLockedByUser = state.categoryLockedByUser,
                createdAt = original?.createdAt ?: now,
                updatedAt = now,
            )
            if (mode == FormMode.EDIT) transactionRepository.update(transaction) else transactionRepository.upsert(transaction)
            isSaving.value = false
            eventChannel.send(TransactionFormEvent.NavigateBack)
        }
    }

    fun onDelete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            transactionRepository.delete(id)
            eventChannel.send(TransactionFormEvent.NavigateBack)
        }
    }

    fun onBackRequested() {
        if (isDirty.value) {
            showDiscardConfirmation.value = true
        } else {
            viewModelScope.launch { eventChannel.send(TransactionFormEvent.NavigateBack) }
        }
    }

    fun onDiscardConfirmed() {
        showDiscardConfirmation.value = false
        viewModelScope.launch { eventChannel.send(TransactionFormEvent.NavigateBack) }
    }

    fun onDiscardCancelled() {
        showDiscardConfirmation.value = false
    }

    private fun markDirty() {
        savedStateHandle[KEY_DIRTY] = true
    }

    private data class EditableFields(
        val amountText: String,
        val type: TransactionType,
        val date: LocalDate,
        val description: String,
        val categoryId: String?,
    )

    private data class MetaFields(
        val categoryLockedByUser: Boolean,
        val notes: String,
        val isDirty: Boolean,
        val submitAttempted: Boolean,
        val isSaving: Boolean,
    )

    private data class LoadState(val isLoaded: Boolean, val error: String?)

    private companion object {
        const val KEY_AMOUNT_TEXT = "amountText"
        const val KEY_TYPE = "type"
        const val KEY_DATE_EPOCH_DAYS = "dateEpochDays"
        const val KEY_DESCRIPTION = "description"
        const val KEY_CATEGORY_ID = "categoryId"
        const val KEY_CATEGORY_LOCKED = "categoryLockedByUser"
        const val KEY_NOTES = "notes"
        const val KEY_DIRTY = "isDirty"
        const val KEY_SUBMIT_ATTEMPTED = "submitAttempted"
        const val KEY_SEEDED = "hasSeededFromRepository"

        // No accounts feature exists yet -- every manual entry gets this fixed
        // id, matching DebugSeeder's sample data. Revisit once accounts are
        // their own feature.
        const val DEFAULT_ACCOUNT_ID = "acct-1"
        const val DEFAULT_CURRENCY_CODE = "INR"
    }
}
