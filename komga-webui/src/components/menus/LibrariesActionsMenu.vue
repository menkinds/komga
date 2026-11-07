<template>
  <div>
    <v-menu offset-y>
      <template v-slot:activator="{ on }">
        <v-btn icon v-on="on" @click.prevent="">
          <v-icon>mdi-dots-vertical</v-icon>
        </v-btn>
      </template>
      <v-list dense>
        <v-list-item @click="reorder">
          <v-list-item-title>{{ $t('common.reorder') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="scan(false)" v-if="isAdmin">
          <v-list-item-title>{{ $t('server.server_management.button_scan_libraries') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="scan(true)" class="list-warning" v-if="isAdmin">
          <v-list-item-title>{{ $t('server.server_management.button_scan_libraries_deep') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="confirmEmptyTrash = true" v-if="isAdmin">
          <v-list-item-title>{{ $t('server.server_management.button_empty_trash') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="unhideModal = true" v-if="hiddenCount > 0">
          <v-list-item-title>{{ $t('hidden_libraries.unhide_menu') }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>

    <confirmation-dialog
      v-model="confirmEmptyTrash"
      :title="$t('dialog.empty_trash.title')"
      :body="$t('dialog.empty_trash.body')"
      :button-confirm="$t('dialog.empty_trash.button_confirm')"
      @confirm="emptyTrash"
    />

    <libraries-unhide-dialog v-model="unhideModal"/>
  </div>
</template>
<script lang="ts">
import Vue from 'vue'
import ConfirmationDialog from '@/components/dialogs/ConfirmationDialog.vue'
import LibrariesUnhideDialog from '@/components/dialogs/LibrariesUnhideDialog.vue'

export default Vue.extend({
  name: 'LibrariesActionsMenu',
  components: {ConfirmationDialog, LibrariesUnhideDialog},
  data: () => {
    return {
      confirmEmptyTrash: false,
      unhideModal: false,
    }
  },
  computed: {
    hiddenCount(): number {
      return this.$store.getters.getLibrariesHidden.length
    },
    isAdmin(): boolean {
      return this.$store.getters.meAdmin
    },
  },
  methods: {
    reorder() {
      this.$emit('reorder')
    },
    scan(scanDeep: boolean) {
      this.$store.state.komgaLibraries.libraries.forEach(library => {
        this.$komgaLibraries.scanLibrary(library, scanDeep)
      })
    },
    emptyTrash() {
      this.$store.state.komgaLibraries.libraries.forEach(library => {
        this.$komgaLibraries.emptyTrash(library)
      })
    },
  },
})
</script>
<style scoped>
@import "../../styles/list-warning.css";
</style>
