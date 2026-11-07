import {AxiosInstance} from 'axios'
import _Vue from 'vue'
import KomgaSettingsService from '@/services/komga-settings.service'
import {Module} from 'vuex'
import {
  CLIENT_SETTING,
  ClientSettingDto,
  ClientSettingHiddenLibrariesLock,
  ClientSettingLibrary,
  ClientSettingLibraryUpdate,
  ClientSettingUserUpdateDto,
} from '@/types/komga-clientsettings'
import {sha256} from '@/functions/sha256'

let service: KomgaSettingsService

const vuexModule: Module<any, any> = {
  state: {
    clientSettingsGlobal: {} as Record<string, ClientSettingDto>,
    clientSettingsUser: {} as Record<string, ClientSettingDto>,
  },
  getters: {
    getClientSettings(state): Record<string, ClientSettingDto> {
      return {...state.clientSettingsGlobal, ...state.clientSettingsUser}
    },
    getClientSettingsLibraries(state): Record<string, ClientSettingLibrary> {
      let settings: Record<string, ClientSettingLibrary> = {}
      try {
        settings = JSON.parse(state.clientSettingsUser[CLIENT_SETTING.WEBUI_LIBRARIES]?.value)
      } catch (e) {
      }
      return settings
    },
    getHiddenLibrariesLock(state): ClientSettingHiddenLibrariesLock | undefined {
      try {
        const lock = JSON.parse(state.clientSettingsUser[CLIENT_SETTING.WEBUI_HIDDEN_LIBRARIES_LOCK]?.value)
        if (lock?.salt && lock?.hash) return lock
      } catch (e) {
      }
      return undefined
    },
  },
  mutations: {
    setClientSettingsGlobal(state, settings) {
      state.clientSettingsGlobal = settings
    },
    setClientSettingsUser(state, settings) {
      state.clientSettingsUser = settings
    },
  },
  actions: {
    async getClientSettingsGlobal({commit}) {
      commit('setClientSettingsGlobal', await service.getClientSettingsGlobal())
    },
    async getClientSettingsUser({commit}) {
      commit('setClientSettingsUser', await service.getClientSettingsUser())
    },
    async updateLibrarySetting({dispatch, getters}, update: ClientSettingLibraryUpdate) {
      const all = getters.getClientSettingsLibraries
      all[update.libraryId] = Object.assign({}, all[update.libraryId], update.patch)
      const newSettings = {} as Record<string, ClientSettingUserUpdateDto>
      newSettings[CLIENT_SETTING.WEBUI_LIBRARIES] = {
        value: JSON.stringify(all),
      }
      await service.updateClientSettingUser(newSettings)
      dispatch('getClientSettingsUser')
    },
    async updateLibrariesSettings({dispatch, getters}, updates: ClientSettingLibraryUpdate[]) {
      const all = getters.getClientSettingsLibraries
      updates.forEach(u => all[u.libraryId] = Object.assign({}, all[u.libraryId], u.patch))

      const newSettings = {} as Record<string, ClientSettingUserUpdateDto>
      newSettings[CLIENT_SETTING.WEBUI_LIBRARIES] = {
        value: JSON.stringify(all),
      }
      await service.updateClientSettingUser(newSettings)
      dispatch('getClientSettingsUser')
    },
    async setHiddenLibrariesPassword({dispatch}, password: string) {
      const salt = Array.from({length: 16}, () => Math.floor(Math.random() * 256).toString(16).padStart(2, '0')).join('')
      const newSettings = {} as Record<string, ClientSettingUserUpdateDto>
      newSettings[CLIENT_SETTING.WEBUI_HIDDEN_LIBRARIES_LOCK] = {
        value: JSON.stringify({salt: salt, hash: sha256(salt + password)} as ClientSettingHiddenLibrariesLock),
      }
      await service.updateClientSettingUser(newSettings)
      await dispatch('getClientSettingsUser')
    },
  },
}

export default {
  install(
    Vue: typeof _Vue,
    {store, http}: { store: any, http: AxiosInstance }) {
    service = new KomgaSettingsService(http)
    Vue.prototype.$komgaSettings = new KomgaSettingsService(http)

    store.registerModule('komgaSettings', vuexModule)
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaSettings: KomgaSettingsService;
  }
}
