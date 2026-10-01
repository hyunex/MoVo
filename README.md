# MoVo (모보)

**MoVo**는 Android용 MPV 기반 로컬 영상 라이브러리 플레이어입니다.
현재 버전은 **1.0.29 (versionCode 30)**이며, SAF로 등록한 폴더의 영상을 탐색하고 재생합니다.

---

## 주요 기능

### 1. 로컬 영상 라이브러리 및 탐색
- **SAF 기반 폴더 등록 & 영속성**: 한 번 등록한 로컬 영상 폴더는 앱을 재실행해도 지속 유지 (매번 파일 탐색기에서 재선택 불필요).
- **계층형 폴더 구조 탐색**: 시즌/에피소드 등 복잡한 하위 폴더 계층을 브레드크럼과 함께 직관적으로 탐색.
- **풍부한 메타데이터 & 제목 표시**:
  - 긴 영상 제목도 잘림 없이 온전히 표시 (최대 3줄 줄바꿈 지원).
  - 파일 크기, 수정 일자, 시청 진행도(%), 남은 시간 등 상세 정보 노출.
  - 시청 완료 여부는 썸네일 우측 상단의 깔끔한 녹색 체크 뱃지(`✓`)로 즉시 식별.
- **이어보기 재생목록 선택**: 설정의 `이어보기 재생 목록`에서 원본 동영상 폴더(기본값) 또는 화면의 이어보기 목록을 선택. 원본 폴더 모드는 같은 실제 하위 디렉터리의 영상만 이름순으로 재생.
- **강력한 라이브러리 관리 (배치 조작)**:
  - 우측 체크박스 또는 롱프레스로 다중 선택 모드 진입.
  - 전체 선택 / 선택 해제.
  - 선택 영상 묶음 재생.
  - 일괄 시청 완료 / 미시청 전환 및 재생 기록 초기화.
  - 확인창을 거쳐 실제 파일 삭제를 요청하고, 삭제에 성공한 파일만 DB에서 제거.
  - 영상 항목을 **왼쪽으로 스와이프**하면 삭제 확인창 표시. 취소하면 파일 유지; 확인하면 SAF로 실제 파일과 라이브러리 기록 삭제. 다중 선택 중에는 스와이프 삭제 비활성화.
  - 폴더 권한을 잃은 경우 해당 폴더에서 권한을 다시 승인한 뒤 보류한 삭제 확인 절차 재개.

### 2. 직관적인 풀스크린 제스처 엔진
- **좌측 세로 스와이프**: 화면 밝기 미세 조절 (`☀ 0% ~ 100%` 실시간 중앙 HUD).
- **우측 세로 스와이프**: 시스템 미디어 음량 조절 (`🔊 0% ~ 100%` 실시간 중앙 HUD).
- **가로 스와이프**: 재생 위치 정밀 탐색 (`12:31 → 15:42 (+03:11)` 실시간 타임 HUD).
- **더블탭 액션**:
  - 좌측 더블탭: 10초 뒤로 탐색 (`⏪ -10초`).
  - 우측 더블탭: 10초 앞으로 탐색 (`⏩ +10초`).
  - 중앙 더블탭: 재생 / 일시정지 즉시 전환.
- **핀치 줌 (Pinch-to-Zoom)**: 두 손가락 제스처로 영상 확대/축소 (`🔍 50% ~ 300%`).
- **롱프레스 2배속 재생**: 화면을 누르고 있는 동안 `⚡ 2.0x 쾌속 재생`, 손을 떼면 기존 속도로 자동 복귀.
- **컨트롤 자동 숨김**: 화면 탭으로 컨트롤 토글 및 4.5초 미조작 시 자연스러운 페이드아웃.

### 3. 전문 비디오 플레이어 컨트롤
- **인터랙티브 Seek Bar**: 드래그 스크러빙 및 터치 즉시 탐색 Slider.
- **중앙 미디어 컨트롤**: 이전 영상, 10초 뒤로, 재생/일시정지, 10초 앞으로, 다음 영상. 주 재생 버튼은 64dp이며 하단에 중복 배치하지 않고 영상 캔버스의 절대 중앙에 정렬.
- **긴 제목 및 좁은 화면**: 상단 제목을 탭하면 전체 제목 다이얼로그 표시. 좁은 화면의 도구 모음은 가로 스크롤.
- **화면 비율 순환 버튼 (`[⛶ 맞춤]`)**:
  - 기본 맞춤 (Best Fit) ↔ 화면 채우기 (Fit Screen) ↔ 비율 무시 늘리기 (Fill) ↔ 16:9 고정 ↔ 4:3 고정 ↔ 원본 크기 (1:1).
- **상단 통합 재생 속도 (`[⏱ 1.2x]`)**:
  - 커스텀 프리셋과 `0.1x ~ 5.0x` 슬라이더/스텝으로 값을 선택한 뒤 **적용**으로 확정. **취소**하면 원래 속도 유지.
  - 설정의 `일괄 편집`에서 프리셋을 쉼표·공백·줄바꿈으로 입력 (예: `1, 1.1, 1.25, 1.33, 1.5`). 잘못된 값은 저장하지 않고 오류 표시.
  - `배속 조절 최소 단위 (Step)`에서 `0.01 ~ 1.0` 범위의 단위 지정 (기본값 `0.05`, 소수점 둘째 자리까지). 플레이어의 `+`/`−` 버튼과 슬라이더에 적용되며 재실행 후에도 유지.
- **자막 선택 다이얼로그 (`[⌨]`)**:
  - 영상 내장 자막 스트림 선택 및 자막 끄기.
  - 외부 자막 파일 불러오기 (`.srt`, `.vtt`, `.ass`, `.smi`).
  - 자막 싱크(지연 시간) 100ms 단위 미세 조절.
- **오디오 트랙 선택 다이얼로그 (`[♪]`)**:
  - 다중 음성/오디오 스트림 선택 및 오디오 싱크 미세 조절.
- **화면 회전 허용/고정 (`[⟳]`)**:
  - 센서 자동 회전 ↔ 가로 모드 고정 ↔ 세로 모드 고정 순환.
- **화면 잠금 (`[🔒]`)**:
  - 시청 중 의도치 않은 터치 방지 및 플로팅 언락 버튼 제공.
- **외부 미디어 키 지원**: Bluetooth 이어폰, 헤드셋, 차량 핸들 리모컨 키 지원.

### 4. MPV 엔진 및 설정
- **libmpv 하드웨어 가속**: mpv `0.41.0-922-gf4d13e1c2` 및 FFmpeg 9 네이티브 라이브러리와 MoVo에서 수정·재빌드한 JNI 브리지 사용.
- **영상 세로 정렬 (자연어 선택)**:
  - 상단 (위쪽 정렬): 폴더블 내부 화면 및 한손 파지 시 상단 배치 최적화.
  - 중앙 (가운데 정렬): 표준 기본값.
  - 하단 (아래쪽 정렬).
- **고급 MPV 설정 (mpv.conf)**: 모달 창을 통해 `hwdec=auto`, `profile=fast` 등 고급 옵션 직접 입력 지원.
- 파일 쓰기·외부 스크립트/설정 로딩 등 허용하지 않는 옵션은 이유와 함께 거부하며 편집창을 유지. 기존 저장 설정의 차단 옵션은 재생 시 필터링하고 안내 배너 표시.

### 5. 화면 크기 대응
- Edge-to-Edge와 Safe Drawing Insets를 사용.
- 영상 목록은 **리스트 형태를 유지**하며 휴대폰/넓은 화면에 맞춰 패널 배치를 조정. 다열 그리드로 전환하지 않음.

---

## 기술 스택 및 아키텍처

- **언어**: Kotlin 1.9.24
- **UI 프레임워크**: Jetpack Compose (Material 3 BOM 2024.06.00)
- **로컬 DB & 영속성**: Room 2.6.1 SQLite (`FolderEntity`, `VideoEntity`), SharedPreferences
- **미디어 재생 엔진**: libmpv `0.41.0-922-gf4d13e1c2` + 수정 JNI 브리지
- **파일 접근**: Android Storage Access Framework (SAF), `DocumentsContract` 디렉터리/메타데이터 쿼리
- **타깃 SDK**: `compileSdk 34`, `targetSdk 34`, `minSdk 26` (Android 8.0 Oreo 이상)

---

## v1.0.29 변경 사항

1. **불완전 스캔에서 기록 보존**: 실패·보류·깊이 24 제한·순환 경로에서 기존 영상 행과 시청 진행 기록을 보존하고 실패 상태와 재시도를 표시.
2. **폴더·권한 중복 처리**: 동일 SAF 폴더 중복 등록을 방지하고 Room v2 마이그레이션으로 기존 중복을 정리하며 기록을 보존. 다른 등록 폴더가 공유하는 영속 SAF 권한은 함께 반환하지 않음.
3. **재생 종료 구분**: JNI의 `START(long ID)` / `END(reason, error, long ID)`로 64비트 항목 식별자를 보존. 정상 EOF·수동 STOP·오류·오래된 이벤트를 구분하고 현재 항목의 정상 EOF만 자동 다음 재생으로 처리하여 건너뛰기 방지.
4. **MPV 옵션 제한**: 파일 쓰기·외부 스크립트/설정 로딩 옵션을 차단 이유와 함께 거부하고 편집창 유지. 기존 저장 설정의 차단 옵션은 안전하게 필터링하고 재생 중 안내.
5. **작업 수명·순서**: 화면 수명과 진행 기록 저장/확인된 파일 삭제 작업을 분리. 앱 범위 FIFO로 기록·DB 변경을 직렬화하고 0초로 뒤로 탐색한 기록도 저장. 확인된 삭제는 화면 이동 뒤에도 계속하며 실제 삭제 성공 항목만 DB에서 제거.
6. **제목·삭제·설정 조작**: 재생 제목을 탭하면 전체 제목 표시, 삭제 확인창에는 대상 파일명·경로 표시. 속도 프리셋/슬라이더/스텝을 모두 적용·취소로 확정하고 설정 카테고리 탐색 제공. 좁은 화면 도구 모음은 가로 스크롤.
7. **로그·배포·출처**: 일반 공유에 보관된 크래시도 포함하되 공유 경계에서 경로·URI·제목(인코딩된 형태 포함)을 가림. 로컬 원본과 기술적 예외 정보는 유지. 전용 릴리스 서명 계보, 고정 네이티브 출처와 소스 22개 아카이브/공개 바이너리 매니페스트를 배포 절차에 포함.
8. **접근성·대규모 목록**: 48dp 메뉴 터치 영역과 파일명·시청/진행/선택 상태·메뉴 동작/대상 접근성 정보 제공. 디렉터리별 단일 쿼리, 400개 DB 배치, 같은 폴더 스캔 병합 및 취소 처리. 자연 정렬 키를 항목별 한 번 계산하고 파생 목록을 메모화. 썸네일은 URI·크기·수정시각 키, 직렬화된 캐시 미스, 원자적 JPEG 게시를 사용하며 API 27+는 256px 축소 추출(API 26은 임시 전체 프레임 대체 경로). 32회 쓰기마다 50MiB/500개 기준 정리하므로 삭제 성공 시에도 다음 정리 전 새 JPEG 최대 31개가 추가될 수 있음.

별도 플레이어 배치 변경: 컨트롤이 표시될 때 **64dp 주 재생/일시정지 버튼을 영상 캔버스의 절대 중앙**에 배치하고 하단 중복 버튼을 제거했습니다.

확인된 범위: `compileDebugKotlin`과 단위 회귀 22개 통과, NDK 29로 세 ABI JNI 실제 컴파일 및 `readelf` ABI/링크 확인 통과. `record-binaries`는 기존 엔진 라이브러리 27개 유지와 JNI 3개 변경을 검증했고, 소스 22개 준비·기존 의존성의 오프라인 다운로드 생략·변조 SHA/기존 출력 경로 거부를 확인했습니다. 실제 Android API 33에서 Room v2 마이그레이션(중복 폴더 정리 및 진행/메타데이터 보존), SAF 루트 재사용, 1,200개 스캔(디렉터리당 1회 쿼리·스캔 병합·불완전 스캔 DB 보존), 잘못된 미디어 오류 표시(`error=-17`) 및 건너뛰기 방지, 0.4초 서브폴 클립의 자연 EOF 시청 완료(`0.4/0.4s`) 저장, 64dp 중앙 재생 버튼과 제목 다이얼로그, 파일 삭제 확인 및 DB 동기화, AppLog 공유 경계 마스킹, URI·크기·수정시각 메타데이터 썸네일 캐시 갱신을 검증했습니다. release 서명 스크립트로 4개 배포용 APK를 생성하고, Android 33 실제 기기에서 debug 신원에서 전용 release 신원으로의 인플레이스 키 회전 업그레이드 및 앱 정상 실행을 확인했습니다.

## 빌드 및 설치

### 전제 조건
- OpenJDK 17, Python 3.12 이상
- Android SDK 34, Build-Tools 34.0.0, Platform-Tools
- JNI 재빌드: Android NDK **29.0.14206865**, 대상 API **26**, 기본 호스트 x86_64 Linux
- 아래 명령은 저장소 루트 기준. `ANDROID_SDK_ROOT`를 실제 SDK 위치로 설정.

### 디버그 / 서명 전 릴리스 빌드
```bash
(cd mpv-player && ./gradlew :app:assembleDebug)
(cd mpv-player && ./gradlew :app:assembleRelease)
```

디버그 산출물: `mpv-player/app/build/outputs/apk/debug/`.
릴리스 산출물: `mpv-player/app/build/outputs/apk/release/app-<abi>-release-unsigned.apk`
(`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`). 릴리스 Gradle 설정은 `isDebuggable=false`이며 debug signing config를 사용하지 않습니다. **unsigned APK는 배포하지 않습니다.**

### 릴리스 서명과 키 보관
먼저 공개된 v1.0.28 arm64 APK를 받아 아래 경로에 둡니다. 해당 APK와 인증서가 일치하는 기존 개인 키가 필요하며, 일치하지 않으면 스크립트가 중단합니다.

```bash
python3 package-release.py \
  --build-tools "$ANDROID_SDK_ROOT/build-tools/34.0.0" \
  --previous-apk "$HOME/Downloads/MoVo-v1.0.28-arm64.apk"
```

기본 이전 키는 `~/.android/debug.keystore`의 `androiddebugkey`입니다. 별도 이전 키는 `OLD_KEYSTORE`, `OLD_ALIAS`, `OLD_PASS_FILE`로 지정합니다. 스크립트는 저장소 밖 `~/.config/movo-signing/`에 암호화된 RSA 3072 JKS(`release.jks`), 암호 파일(`release.pass`), 서명 계보(`release.lineage`)를 생성·유지합니다. 디렉터리 권한은 0700, 개인 파일은 0600입니다.

- `--rotation-min-sdk-version 28`: **SDK 26/27은 기존 debug 서명 신원을 유지**하여 업데이트 호환성을 보존하고, **SDK 28+는 새 전용 릴리스 신원**을 사용합니다. 따라서 이전 개인 키도 계속 필요합니다.
- 성공 시 `release-artifacts/1.0.29/`에 `MoVo-v1.0.29-{arm64,armv7,x86_64,universal}.apk` 네 개와 공개 DER 인증서·계보·검증 기록(`signing-evidence.json` 등)을 작성합니다. 기존 출력 경로는 덮어쓰지 않습니다.
- **새 키·암호·기존 개인 키/암호와 공개 계보를 저장소 밖에 백업**하세요. 개인 키 손실은 업데이트를 막습니다. 개인 키와 암호는 Git/GitHub 또는 배포 자산에 올리지 마세요. 공개 인증서/계보는 배포할 수 있습니다.
- 위 내용은 실행 절차이며, 실제 인증서 지문 및 서명 결과는 생성된 검증 기록으로 확인해야 합니다.

### 고정된 네이티브 소스 준비 / JNI 재빌드
중앙 목록은 `mpv-player/native/sources.lock.json`입니다. 22개 아카이브의 URL·리비전·크기·SHA256 및 중첩 서브모듈 경로가 고정되어 있습니다.

```bash
# 처음에만 --download로 누락된 아카이브 다운로드 허용
python3 package-native-sources.py prepare \
  --cache-dir .native-source-cache --download --output native-prepared

# 같은 캐시를 사용하는 후속 준비는 --download 없이 새 출력 경로로 실행
# 원본 엔진 라이브러리가 jniLibs에 있는 상태에서 JNI만 세 ABI 재빌드
python3 mpv-player/app/buildNative.py \
  --ndk "$ANDROID_SDK_ROOT/ndk/29.0.14206865" \
  --archive-dir .native-source-cache

# 반드시 JNI 재빌드 후 기록; 각 출력 파일은 아직 존재하지 않아야 함
python3 package-native-sources.py record-binaries --output native-binaries.json
python3 package-native-sources.py bundle \
  --cache-dir .native-source-cache --binary-manifest native-binaries.json \
  --output MoVo-corresponding-source.tar.gz
```

`prepare`는 원본 고정 mpv-android 빌드 스크립트와 모든 의존성, libplacebo/FreeType 중첩 서브모듈을 `native-prepared/buildscripts/`에 배치합니다. 공식 mbedTLS 아카이브는 framework도 포함합니다. 기존 의존성이 준비되어 있으면 원본 `download-deps.sh`는 이를 재사용하며 floating clone/download가 필요하지 않습니다. 전체 엔진을 재빌드하려면 이 준비된 트리의 원본 빌드 스크립트와 요구 도구를 사용한 뒤, 수정 JNI를 빌드하고 Gradle로 APK를 만드세요. **위 `buildNative.py` 자체는 전체 엔진을 컴파일하지 않으며**, 패키징된 `libc++_shared.so` 등 엔진 라이브러리에 링크합니다.

ARM Linux 호스트에서는 Android SDK/NDK의 x86_64 호스트 도구 실행 환경을 별도로 제공해야 합니다. x86_64 런타임(libz·libstdc++ 포함)과 QEMU가 준비된 경우의 JNI 예:
```bash
QEMU_LD_PREFIX=/usr/x86_64-linux-gnu python3 mpv-player/app/buildNative.py \
  --ndk "$ANDROID_SDK_ROOT/ndk/29.0.14206865" \
  --archive-dir .native-source-cache \
  --runner 'qemu-x86_64-static -L /usr/x86_64-linux-gnu -E LD_LIBRARY_PATH=/usr/x86_64-linux-gnu/lib'
```
이는 Android용 ARM 바이너리를 호스트 도구로 사용하는 명령이 아닙니다. Gradle의 SDK 도구에도 해당 호스트의 실행 환경이 필요합니다.

소스 묶음에는 앱·수정 JNI·빌드 스크립트·라이선스, **실제 소스 아카이브 22개**, lock 및 공개 바이너리 매니페스트가 포함됩니다. 사전 컴파일 `.so`, 개인 키/암호, 빌드 산출물/로그/임시 검증 파일은 제외합니다. 생성된 `.sha256`과 함께 [v1.0.29 릴리스](https://github.com/hyunex/MoVo/releases/tag/v1.0.29)의 `MoVo-corresponding-source.tar.gz` 자산으로 제공하는 절차입니다. 고정 입력과 대응 소스 제공은 전체 엔진의 바이트 동일 재컴파일 보증이 아닙니다. 상세 출처와 증거 한계는 [서드파티 고지](THIRD-PARTY-NOTICES.md)를 참조하세요.

### adb 설치
```bash
adb install -r release-artifacts/1.0.29/MoVo-v1.0.29-arm64.apk
```

---

## 라이선스
MoVo는 [GNU General Public License v3.0](LICENSE) 하에 배포됩니다.
동봉된 `libmpv.so`/FFmpeg 네이티브 바이너리가 GPL 빌드이므로 배포물 전체에 GPLv3가 적용됩니다.
서드파티 고지 및 바이너리 출처는 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)를 참조하세요.
