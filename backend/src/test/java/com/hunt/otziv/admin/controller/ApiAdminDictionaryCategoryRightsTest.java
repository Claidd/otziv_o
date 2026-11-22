package com.hunt.otziv.admin.controller;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.repository.CategoryRepository;
import com.hunt.otziv.c_categories.repository.SubCategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Managers read, add and rename categories; only the owner and administrators delete them. */
class ApiAdminDictionaryCategoryRightsTest {

    private static final String OWNER_AND_ADMIN = "hasAnyRole('ADMIN', 'OWNER')";
    private static final String WITH_MANAGERS = "hasAnyRole('ADMIN', 'OWNER', 'MANAGER')";

    @Test
    void managersAddAndRenameCategories() throws Exception {
        Class<ApiAdminDictionaryController> controller = ApiAdminDictionaryController.class;
        assertRole(controller.getMethod("createCategory", ApiAdminDictionaryController.TitleRequest.class), WITH_MANAGERS);
        assertRole(controller.getMethod("updateCategory", Long.class, ApiAdminDictionaryController.TitleRequest.class), WITH_MANAGERS);
        assertRole(controller.getMethod("createSubCategory", ApiAdminDictionaryController.SubCategoryRequest.class), WITH_MANAGERS);
        assertRole(controller.getMethod("updateSubCategory", Long.class, ApiAdminDictionaryController.SubCategoryRequest.class), WITH_MANAGERS);
    }

    @Test
    void onlyOwnerAndAdministratorsDeleteCategories() throws Exception {
        Class<ApiAdminDictionaryController> controller = ApiAdminDictionaryController.class;
        assertRole(controller.getMethod("deleteCategory", Long.class), OWNER_AND_ADMIN);
        assertRole(controller.getMethod("deleteSubCategory", Long.class), OWNER_AND_ADMIN);
    }

    @Test
    void categoryReadsKeepTheUrlRuleThatIncludesManagers() throws Exception {
        Class<ApiAdminDictionaryController> controller = ApiAdminDictionaryController.class;
        assertThat(controller.getMethod("getCategories", String.class).getAnnotation(PreAuthorize.class)).isNull();
        assertThat(controller.getMethod("getSubCategories", Long.class, String.class).getAnnotation(PreAuthorize.class)).isNull();
    }

    @Test
    void usedCategoryIsRefusedAndKept() {
        CategoryRepository repository = mock(CategoryRepository.class, CALLS_REAL_METHODS);
        doReturn(Optional.of(new Category())).when(repository).findLockedById(7L);
        doReturn(3L).when(repository).countUsagesByCategoryId(7L);

        assertThatThrownBy(() -> repository.deleteUnusedCategory(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo(CategoryRepository.CATEGORY_IN_USE);
                });
        verify(repository, never()).deleteById(7L);
    }

    @Test
    void unusedCategoryIsDeletedAndMissingOneIsNotFound() {
        CategoryRepository repository = mock(CategoryRepository.class, CALLS_REAL_METHODS);
        doReturn(Optional.of(new Category())).when(repository).findLockedById(7L);
        doReturn(0L).when(repository).countUsagesByCategoryId(7L);
        doReturn(Optional.empty()).when(repository).findLockedById(8L);
        org.mockito.Mockito.doNothing().when(repository).deleteById(7L);

        repository.deleteUnusedCategory(7L);

        verify(repository).deleteById(7L);
        assertThatThrownBy(() -> repository.deleteUnusedCategory(8L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void usedSubCategoryIsRefusedAndKept() {
        SubCategoryRepository repository = mock(SubCategoryRepository.class, CALLS_REAL_METHODS);
        doReturn(Optional.of(new SubCategory())).when(repository).findLockedById(5L);
        doReturn(1L).when(repository).countUsagesBySubCategoryId(5L);

        assertThatThrownBy(() -> repository.deleteUnusedSubCategory(5L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo(SubCategoryRepository.SUBCATEGORY_IN_USE);
                });
        verify(repository, never()).deleteById(5L);
    }

    private static void assertRole(Method method, String expected) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).as(method.getName()).isNotNull();
        assertThat(preAuthorize.value()).as(method.getName()).isEqualTo(expected);
    }
}
