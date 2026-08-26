# Privacy

## English

lightstop processes camera frames and motion-sensor samples locally to calculate
exposure, track Zone markers, and provide related photography tools. It does not
request the INTERNET permission, include advertising or analytics SDKs, or send
camera frames, meter readings, location, notes, or settings to the developer or
a third-party server.

Camera permission is required for viewfinding and metering. Location permission
is optional and is used only after the user enables location for parameter
records and grants permission. The app may read a recent location or request one
current location and attach it to a record the user chooses to save. It does not
continuously track location in the background.

When the user saves a parameter record, the app stores a viewfinder JPEG,
exposure parameters, time, notes, and an optional authorized location in
app-private storage. If RAW recording is enabled, a DNG may also be stored.
Ordinary live-preview frames and unsaved meter readings are not retained as
parameter photographs.

Android or device-manufacturer backup, cloud-backup, and device-transfer services
may copy app-private settings, calibration data, JPEGs, DNGs, locations, and
notes according to the user's system settings. Such transfers are performed by
the operating system or device vendor, not by lightstop or its developer. The
developer cannot access copies held in the user's system account. Backup,
encryption, retention, and restore behavior depend on the device, OS version,
account, vendor policy, and quota. Large DNG files are not guaranteed to be
backed up.

Users can delete parameter records in the app and can disable backup for the app
in system settings. Uninstalling normally removes app-private data from the
device, but copies already held by a system backup service may remain subject to
that service's retention policy.

The source code is publicly auditable. A distributor who modifies the project by
adding networking, analytics, crash reporting, other cloud synchronization, or
another data processor must publish a privacy policy describing those changes.

## 中文

光档在设备本地处理相机画面和运动传感器采样，用于计算曝光、跟踪 Zone
标记点和提供相关摄影工具。应用不申请 INTERNET 权限，不包含广告或统计
SDK，也不会由应用或开发者把相机画面、测光数据、位置、备注或设置上传到
开发者服务器或第三方服务。

应用需要相机权限才能提供取景和测光。位置权限是可选的，仅在用户主动启用
参数记录的位置选项并授权后使用。应用可能读取最近位置或请求一次当前位置，
并把获得的位置写入用户主动保存的参数记录；应用不在后台持续跟踪位置。

当用户主动保存参数记录时，应用会在应用私有存储中保存取景 JPEG、曝光参数、
时间、备注和用户选择记录的位置；启用 RAW 记录时还可能保存 DNG。普通取景
和未保存的测光画面不会作为参数照片长期保留。

Android 或设备厂商提供的系统备份、云备份和换机服务可能按照用户的系统设置，
将应用私有目录中的设置、校准数据、JPEG、DNG、位置和备注复制到系统备份服务
或另一台设备。这些传输由操作系统或设备厂商执行，并非由光档或开发者上传。
开发者无法访问用户系统账号中的备份副本。是否备份、加密、保留或成功恢复取决于
设备、系统版本、账号、厂商策略和备份配额；较大的 DNG 不保证能够进入备份。

用户可以在应用中删除参数记录，也可以在系统设置中关闭本应用的备份。卸载应用通常
会删除设备上的应用私有数据，但已经由系统备份服务保存的副本可能按照相应服务的
保留策略继续存在。

项目源代码可公开审计。若发行者修改项目并加入网络、统计、崩溃上报、其他云同步
或其他数据处理功能，必须更新隐私声明并说明这些变化。
