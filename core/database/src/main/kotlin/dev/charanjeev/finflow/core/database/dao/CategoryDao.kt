package dev.charanjeev.finflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.charanjeev.finflow.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id AND is_system_defined = 0")
    suspend fun deleteUserCategory(id: String)

    /**
     * Seeding uses this instead of [upsertAll]: ignoring a conflict on the
     * fixed system-category ids is what makes reseeding a no-op for any
     * category the user has already renamed or recoloured.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(categories: List<CategoryEntity>): List<Long>
}
