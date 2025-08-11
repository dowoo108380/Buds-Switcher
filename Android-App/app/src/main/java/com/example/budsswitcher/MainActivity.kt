package com.example.budsswitcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    // UI 요소
    private lateinit var spinnerIpList: Spinner
    private lateinit var etNickname: EditText
    private lateinit var etIpAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDelete: Button

    // 데이터 관리
    private val gson = Gson()
    private var pcProfiles = mutableListOf<PcProfile>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    companion object {
        private const val PREFS_NAME = "BudsSwitcherPrefs"
        private const val KEY_PROFILES = "pc_profiles"
        private const val KEY_LAST_SELECTED_NICKNAME = "last_selected_nickname"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI 초기화
        spinnerIpList = findViewById(R.id.spinner_ip_list)
        etNickname = findViewById(R.id.et_nickname)
        etIpAddress = findViewById(R.id.et_ip_address)
        btnConnect = findViewById(R.id.btn_connect)
        btnDelete = findViewById(R.id.btn_delete)

        // 저장된 데이터 불러오기 및 Spinner 설정
        loadProfiles()
        setupSpinner()

        // Spinner 아이템 선택 리스너
        spinnerIpList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position > 0) { // "새로 추가..."가 아닐 경우
                    val selectedProfile = pcProfiles[position - 1]
                    etNickname.setText(selectedProfile.nickname)
                    etIpAddress.setText(selectedProfile.ip)
                } else { // "새로 추가..." 선택 시
                    etNickname.text.clear()
                    etIpAddress.text.clear()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 연결 및 저장 버튼
        btnConnect.setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val ip = etIpAddress.text.toString().trim()

            if (nickname.isNotEmpty() && ip.isNotEmpty()) {
                saveProfile(PcProfile(nickname, ip))
                startService(ip)
            } else {
                Toast.makeText(this, "PC 별명과 IP 주소를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // 삭제 버튼
        btnDelete.setOnClickListener {
            val selectedNickname = etNickname.text.toString().trim()
            if (selectedNickname.isNotEmpty()) {
                deleteProfile(selectedNickname)
            }
        }
    }

    private fun setupSpinner() {
        val spinnerItems = mutableListOf("새로 추가...")
        spinnerItems.addAll(pcProfiles.map { it.nickname }) // 프로필 목록에서 닉네임만 추출하여 추가

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerItems)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerIpList.adapter = spinnerAdapter

        // 마지막으로 선택했던 항목을 자동으로 선택
        val lastNickname = getLastSelectedNickname()
        val lastPosition = spinnerItems.indexOf(lastNickname)
        if (lastPosition > 0) {
            spinnerIpList.setSelection(lastPosition)
        }
    }

    private fun refreshSpinner() {
        val spinnerItems = mutableListOf("새로 추가...")
        spinnerItems.addAll(pcProfiles.map { it.nickname })
        spinnerAdapter.clear()
        spinnerAdapter.addAll(spinnerItems)
        spinnerAdapter.notifyDataSetChanged()
    }

    private fun loadProfiles() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILES, null)
        if (json != null) {
            val type = object : TypeToken<MutableList<PcProfile>>() {}.type
            pcProfiles = gson.fromJson(json, type)
        }
    }

    private fun saveProfilesToPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val json = gson.toJson(pcProfiles)
        editor.putString(KEY_PROFILES, json)
        editor.apply()
    }

    private fun saveProfile(newProfile: PcProfile) {
        val existingProfileIndex =
            pcProfiles.indexOfFirst { it.nickname.equals(newProfile.nickname, ignoreCase = true) }
        if (existingProfileIndex != -1) {
            // 기존 프로필 업데이트 (IP 주소 변경 등)
            pcProfiles[existingProfileIndex] = newProfile
        } else {
            // 새 프로필 추가
            pcProfiles.add(newProfile)
        }
        saveProfilesToPrefs()
        saveLastSelectedNickname(newProfile.nickname)
        refreshSpinner()
    }

    private fun deleteProfile(nickname: String) {
        pcProfiles.removeAll { it.nickname.equals(nickname, ignoreCase = true) }
        saveProfilesToPrefs()
        refreshSpinner()
        // 삭제 후 입력 필드 초기화
        spinnerIpList.setSelection(0)
        Toast.makeText(this, "'$nickname' 프로필을 삭제했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun saveLastSelectedNickname(nickname: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SELECTED_NICKNAME, nickname).apply()
    }

    private fun getLastSelectedNickname(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SELECTED_NICKNAME, null)
    }

    private fun startService(ip: String) {
        // 권한 확인 로직은 생략 (이전 코드와 동일, 필요 시 추가)
        val serviceIntent = Intent(this, BudsSwitcherService::class.java)
        serviceIntent.putExtra("IP_ADDRESS", ip)
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "백그라운드 서비스를 시작합니다.", Toast.LENGTH_SHORT).show()
        finish()
    }
}