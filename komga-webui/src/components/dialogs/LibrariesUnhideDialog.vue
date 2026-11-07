<template>
  <v-dialog v-model="modal" max-width="450">
    <v-card>
      <v-card-title>{{ $t('hidden_libraries.unhide_title') }}</v-card-title>

      <v-card-text>
        <template v-if="!unlocked">
          <p>{{ $t('hidden_libraries.unhide_body') }}</p>
          <v-text-field v-model="password"
                        type="password"
                        autocomplete="current-password"
                        :label="$t('hidden_libraries.password')"
                        :error-messages="wrongPassword ? $t('hidden_libraries.password_wrong') : undefined"
                        autofocus
                        @input="wrongPassword = false"
                        @keydown.enter="checkPassword"
          />
        </template>
        <template v-else>
          <v-checkbox v-for="l in hiddenLibraries"
                      :key="l.id"
                      v-model="selected"
                      :value="l.id"
                      :label="l.name"
                      hide-details
                      dense
          />
        </template>
      </v-card-text>

      <v-card-actions>
        <v-spacer/>
        <v-btn text @click="modal = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn v-if="!unlocked" color="primary" :disabled="password === ''" @click="checkPassword">
          {{ $t('hidden_libraries.unlock') }}
        </v-btn>
        <v-btn v-else color="primary" :disabled="selected.length === 0 || saving" @click="unhide">
          {{ $t('hidden_libraries.unhide') }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import {LibraryDto} from '@/types/komga-libraries'
import {ClientSettingHiddenLibrariesLock, ClientSettingLibraryUpdate} from '@/types/komga-clientsettings'
import {sha256} from '@/functions/sha256'

export default Vue.extend({
  name: 'LibrariesUnhideDialog',
  props: {
    value: Boolean,
  },
  data: () => {
    return {
      modal: false,
      password: '',
      wrongPassword: false,
      unlocked: false,
      selected: [] as string[],
      saving: false,
    }
  },
  watch: {
    value(val) {
      this.modal = val
      if (val) {
        this.password = ''
        this.wrongPassword = false
        this.unlocked = false
        this.selected = this.hiddenLibraries.map(it => it.id)
      }
    },
    modal(val) {
      !val && this.$emit('input', false)
    },
  },
  computed: {
    hiddenLibraries(): LibraryDto[] {
      return this.$store.getters.getLibrariesHidden
    },
  },
  methods: {
    checkPassword() {
      if (this.password === '') return
      const lock = this.$store.getters.getHiddenLibrariesLock as ClientSettingHiddenLibrariesLock | undefined
      // no stored password (setting removed): nothing to check against
      if (!lock || sha256(lock.salt + this.password) === lock.hash) this.unlocked = true
      else this.wrongPassword = true
    },
    async unhide() {
      this.saving = true
      try {
        await this.$store.dispatch('updateLibrariesSettings', this.selected.map(id => ({
          libraryId: id,
          patch: {hidden: false},
        } as ClientSettingLibraryUpdate)))
        this.modal = false
      } finally {
        this.saving = false
      }
    },
  },
})
</script>
