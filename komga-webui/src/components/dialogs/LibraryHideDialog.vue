<template>
  <v-dialog v-model="modal" max-width="450">
    <v-card>
      <v-card-title>{{ $t('hidden_libraries.hide_title', {name: library ? library.name : ''}) }}</v-card-title>

      <v-card-text>
        <p>{{ $t('hidden_libraries.hide_body') }}</p>
        <template v-if="needsPassword">
          <p>{{ $t('hidden_libraries.set_password_body') }}</p>
          <v-text-field v-model="password"
                        type="password"
                        autocomplete="new-password"
                        :label="$t('hidden_libraries.password')"
                        autofocus
          />
          <v-text-field v-model="passwordConfirm"
                        type="password"
                        autocomplete="new-password"
                        :label="$t('hidden_libraries.password_confirm')"
                        :error-messages="mismatch ? $t('hidden_libraries.password_mismatch') : undefined"
                        @keydown.enter="confirm"
          />
        </template>
      </v-card-text>

      <v-card-actions>
        <v-spacer/>
        <v-btn text @click="modal = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" :disabled="!canConfirm" @click="confirm">{{ $t('hidden_libraries.hide') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import {LibraryDto} from '@/types/komga-libraries'
import {ClientSettingLibraryUpdate} from '@/types/komga-clientsettings'
import {LIBRARIES_ALL} from '@/types/library'

export default Vue.extend({
  name: 'LibraryHideDialog',
  props: {
    value: Boolean,
    library: {
      type: Object as () => LibraryDto,
      required: false,
    },
  },
  data: () => {
    return {
      modal: false,
      password: '',
      passwordConfirm: '',
      saving: false,
    }
  },
  watch: {
    value(val) {
      this.modal = val
      if (val) {
        this.password = ''
        this.passwordConfirm = ''
      }
    },
    modal(val) {
      !val && this.$emit('input', false)
    },
  },
  computed: {
    needsPassword(): boolean {
      return !this.$store.getters.getHiddenLibrariesLock
    },
    mismatch(): boolean {
      return this.passwordConfirm !== '' && this.password !== this.passwordConfirm
    },
    canConfirm(): boolean {
      if (this.saving || !this.library) return false
      return !this.needsPassword || (this.password !== '' && this.password === this.passwordConfirm)
    },
  },
  methods: {
    async confirm() {
      if (!this.canConfirm) return
      this.saving = true
      try {
        if (this.needsPassword) await this.$store.dispatch('setHiddenLibrariesPassword', this.password)
        await this.$store.dispatch('updateLibrarySetting', {
          libraryId: this.library.id,
          patch: {hidden: true},
        } as ClientSettingLibraryUpdate)
        if (this.$route.params.libraryId === this.library.id) {
          await this.$router.push({name: 'libraries', params: {libraryId: LIBRARIES_ALL}})
        }
        this.modal = false
      } finally {
        this.saving = false
      }
    },
  },
})
</script>
