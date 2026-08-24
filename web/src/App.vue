<template>
  <a-config-provider :theme="theme">
    <router-view />
  </a-config-provider>
</template>

<script setup>
const theme = {
  token: {
    colorPrimary: '#1d6fa5',
    colorPrimaryHover: '#2b7fab',
    colorPrimaryActive: '#155d8b',
    colorInfo: '#1d6fa5',
    colorSuccess: '#238268',
    colorWarning: '#c98224',
    colorError: '#c44751',
    colorText: '#183247',
    colorTextSecondary: '#667b8b',
    colorBorder: '#d8e2e8',
    colorBorderSecondary: '#e7edf1',
    colorBgLayout: '#edf2f6',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorFillAlter: '#f5f8fa',
    borderRadius: 9,
    borderRadiusLG: 11,
    borderRadiusSM: 7,
    controlHeight: 34,
    controlHeightLG: 40,
    controlHeightSM: 28,
    controlOutline: 'rgba(29, 111, 165, 0.18)',
    controlOutlineWidth: 3,
    fontSize: 13,
    fontSizeHeading1: 24,
    fontSizeHeading2: 19,
    fontSizeHeading3: 16,
    lineHeight: 1.55,
    boxShadowSecondary: '0 14px 40px rgba(18, 47, 65, 0.14)',
    motionDurationMid: '0.18s',
    fontFamily: "Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Button: { borderRadius: 7, controlHeight: 34, fontWeight: 550, defaultShadow: 'none', primaryShadow: '0 3px 9px rgba(29, 111, 165, 0.16)' },
    Card: { headerBg: '#fbfcfd', headerFontSize: 14, paddingLG: 16 },
    Input: { activeBorderColor: '#4b91b8', hoverBorderColor: '#75a9c4', activeShadow: '0 0 0 3px rgba(29, 111, 165, 0.12)' },
    Select: { activeBorderColor: '#4b91b8', hoverBorderColor: '#75a9c4', activeOutlineColor: 'rgba(29, 111, 165, 0.12)', optionSelectedBg: '#eaf3f7' },
    Table: { headerBg: '#f4f7f9', headerColor: '#40596b', rowHoverBg: '#f5fafc', borderColor: '#e2e9ed', headerSplitColor: '#e2e9ed' },
    Menu: { darkItemBg: '#0d2637', darkSubMenuItemBg: '#0a2130', darkItemSelectedBg: '#174c69', darkItemHoverBg: '#12384e' },
    Tag: { borderRadiusSM: 5 },
    Tabs: { itemColor: '#607584', itemHoverColor: '#1d6fa5', itemSelectedColor: '#185f8e', inkBarColor: '#1d6fa5' },
    Modal: { headerBg: '#ffffff', titleColor: '#173247', titleFontSize: 16 },
    Drawer: { colorBgElevated: '#ffffff' },
    Pagination: { itemActiveBg: '#edf5f8' },
    Tooltip: { colorBgSpotlight: '#173247' },
  },
}
</script>
